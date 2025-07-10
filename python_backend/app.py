"""DocBud backend (app.py).

A Flask API that accepts audio uploads, sends them to AssemblyAI for
transcription and diarisation.
Integrates AssemblyAI and optional LLM post-processing (OpenAI or Ollama).
"""

from __future__ import annotations

# ── Standard Library ──────────────────────────────────────────────────────────
import json
import logging
import os
import subprocess
from datetime import timedelta
from typing import Final

# ── Third-Party Packages ──────────────────────────────────────────────────────
from dotenv import load_dotenv
from flask import Flask, jsonify, request
from werkzeug.utils import secure_filename

import assemblyai
from openai import OpenAI
from openai import APIError, APIConnectionError, RateLimitError, AuthenticationError

# ── Constants & Global Config ────────────────────────────────────────────────
UPLOAD_FOLDER: Final[str] = "uploads"
ALLOWED_EXTENSIONS: Final[set[str]] = {
    "mp3",
    "mp4",
    "m4a",
    "wav",
    "3gp",
    "aac",
}

# ── Environment Variables ────────────────────────────────────────────────────
load_dotenv()  # Load .env into process environment

ASSEMBLYAI_API_KEY: Final[str | None] = os.getenv("ASSEMBLYAI_API_KEY")
OPENAI_API_KEY: Final[str | None] = os.getenv("OPENAI_API_KEY")

# ── Flask Application Setup ──────────────────────────────────────────────────
app = Flask(__name__)
app.config["UPLOAD_FOLDER"] = UPLOAD_FOLDER

# ── Logger Configuration ─────────────────────────────────────────────────────
app.logger.setLevel(logging.INFO)

if app.debug:
    # Ensure a single stream handler emitting INFO during debug sessions
    if any(isinstance(h, logging.StreamHandler) for h in app.logger.handlers):
        for handler in app.logger.handlers:
            handler.setLevel(logging.INFO)
    else:
        handler = logging.StreamHandler()
        handler.setLevel(logging.INFO)
        app.logger.addHandler(handler)
else:
    # Production-like default: WARN+
    if not app.logger.handlers:
        logging.basicConfig(level=logging.WARNING)

# ── Filesystem Preparations ──────────────────────────────────────────────────
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
app.logger.info("Upload folder ready at %s", os.path.abspath(UPLOAD_FOLDER))

# ── Third-Party API Clients ──────────────────────────────────────────────────
if not ASSEMBLYAI_API_KEY:
    app.logger.warning(
        "ASSEMBLYAI_API_KEY not found. Transcription requests will fail."
    )
else:
    assemblyai.settings.api_key = ASSEMBLYAI_API_KEY
    app.logger.info("AssemblyAI client configured.")

openai_client: OpenAI | None = None
if not OPENAI_API_KEY:
    app.logger.warning(
        "OPENAI_API_KEY not found. Transcript clean-up with OpenAI will be skipped."
    )
else:
    try:
        openai_client = OpenAI(api_key=OPENAI_API_KEY)
        app.logger.info("OpenAI client configured.")
    except Exception as exc:  # Broad catch because SDK can raise several types
        app.logger.error("Failed to configure OpenAI client: %s", exc, exc_info=True)
        openai_client = None


# ── Transcript Processing Helpers ─────────────────────────────────────────────
# Functions for basic validation and light rule-based cleanup

def allowed_file(filename: str) -> bool:
    """Check if a given filename has an allowed extension.

    Args:
        filename: Name of the uploaded file.

    Returns:
        True if file has an allowed extension, else False.
    """
    return (
        "." in filename
        and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS
    )


def apply_light_rules(text_segments: list[dict]) -> list[dict]:
    """Apply simple, rule-based edits to transcript segments.

    This function tags segments where speaker switch cues are likely
    (e.g., utterances starting with "Yeah" or "No") to be handled
    in downstream cleanup logic.

    Args:
        text_segments: List of diarized transcript segments.

    Returns:
        List of segments with optional speaker_override flags added.
    """
    app.logger.info("Applying light rules (e.g., tagging speaker changes)...")
    cleaned_segments = []

    for segment in text_segments:
        text = segment.get("text", "").strip()
        if text.lower().startswith(("yeah", "no")):
            segment["speaker_override"] = True
        cleaned_segments.append(segment)

    return cleaned_segments


# ── LLM Transcript Cleanup (OpenAI and Local Ollama) ──────────────────────────
# Sends transcript to OpenAI or local LLM for conservative cleanup

def clean_transcript_with_openai_llm(diarized_transcript_text: str) -> str:
    """Cleans a diarized medical transcript using OpenAI's GPT model.

    This function sends the transcript to OpenAI (e.g., GPT-3.5-turbo)
    with a detailed system prompt and returns a minimally cleaned version,
    keeping speaker labels and timestamps intact.

    Args:
        diarized_transcript_text: The raw transcript with speaker and time tags.

    Returns:
        Cleaned transcript string, or an error-prefixed fallback message.
    """
    app.logger.info("Starting transcript cleanup via OpenAI...")

    if not openai_client:
        app.logger.warning("OpenAI client not configured. Skipping cleanup.")
        return f"LLM_SKIPPED: OpenAI client not configured.\n{diarized_transcript_text}"

    system_prompt = (
        "You are an expert in refining diarized medical appointment transcripts. "
        "Your task is to fix only obvious misspellings or grammar errors while "
        "preserving timestamps, speaker labels, and the overall meaning. Do not add, "
        "remove, or guess content. Do not rename speakers unless it is obviously wrong."
    )

    user_prompt = (
        "Please clean the following diarized transcript according to the rules above:\n\n"
        f"{diarized_transcript_text}"
    )

    try:
        response = openai_client.chat.completions.create(
            model="gpt-3.5-turbo",
            temperature=0.2,
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
        )

        cleaned_text = response.choices[0].message.content or ""
        app.logger.info("OpenAI cleanup completed successfully.")
        return cleaned_text

    except APIConnectionError as err:
        app.logger.error("OpenAI connection error: %s", err)
        return f"LLM_ERROR: API connection error.\n{diarized_transcript_text}"

    except RateLimitError as err:
        app.logger.error("OpenAI rate limit exceeded: %s", err)
        return f"LLM_ERROR: API rate limit.\n{diarized_transcript_text}"

    except AuthenticationError as err:
        app.logger.error("OpenAI authentication failed: %s", err)
        return f"LLM_ERROR: Authentication failed.\n{diarized_transcript_text}"

    except APIError as err:
        app.logger.error("OpenAI API error: %s", err)
        return f"LLM_ERROR: OpenAI API error ({err.status_code}).\n{diarized_transcript_text}"

    except Exception as err:
        app.logger.error("Unexpected OpenAI error: %s", err, exc_info=True)
        return f"LLM_ERROR: Unexpected error.\n{diarized_transcript_text}"


def clean_with_local_llm(transcript_text_input: str) -> str:
    """Cleans transcript using a local LLM via Ollama CLI.

    Sends a diarized transcript string to the locally running Ollama
    model (e.g., Mistral) and parses the output into cleaned transcript lines.

    Args:
        transcript_text_input: Diarized transcript in text format.

    Returns:
        Cleaned transcript string, or fallback/error message.
    """
    app.logger.info("Attempting local LLM cleanup using Ollama...")

    prompt = (
        "You are reviewing a diarized medical appointment transcript.\n"
        "Each line includes a timestamp, speaker label, and utterance.\n"
        "Your task is to fix any clear mistakes conservatively.\n\n"
        "Return only the cleaned transcript in this format:\n"
        "[HH:MM:SS] SPEAKER: Utterance\n\n"
        "Original Transcript:\n"
        f"{transcript_text_input}"
    )

    try:
        result = subprocess.run(
            ["ollama", "run", "mistral"],
            input=prompt.encode("utf-8"),
            capture_output=True,
            check=True,
            timeout=120,
        )

        output = result.stdout.decode("utf-8")
        cleaned_lines = [
            line for line in output.splitlines()
            if line.strip().startswith("[") and ":" in line.partition(":")[2]
        ]

        if not cleaned_lines and output:
            app.logger.warning("Ollama returned unstructured output.")
            return f"LLM_RAW_OUTPUT:\n{output}"

        app.logger.info("Local LLM cleanup successful.")
        return "\n".join(cleaned_lines)

    except Exception as err:
        app.logger.error("Local LLM cleanup failed: %s", err, exc_info=True)
        return f"LLM_ERROR: Local LLM processing failed.\n{transcript_text_input}"


# ── Transcription Logic (AssemblyAI) ──────────────────────────────────────────
# Handles audio-to-text conversion using AssemblyAI with speaker diarization.

def perform_transcription_with_assemblyai(audio_path: str) -> str:
    """Transcribes an audio file using AssemblyAI with speaker diarization.

    Sends a standardized WAV file to AssemblyAI, waits for transcription,
    and formats the transcript with timestamps and speaker labels.

    Args:
        audio_path: Path to the audio file to transcribe (should be WAV, mono, 16kHz).

    Returns:
        A string of the formatted transcript, or a fallback error message.
    """
    app.logger.info("Starting AssemblyAI transcription: %s", audio_path)

    if not ASSEMBLYAI_API_KEY:
        app.logger.error("AssemblyAI API key not configured.")
        return "ERROR: AssemblyAI API key not configured."

    try:
        transcriber = assemblyai.Transcriber()
        config = assemblyai.TranscriptionConfig(
            speaker_labels=True,
            speech_model=assemblyai.SpeechModel.best
        )

        app.logger.info("Uploading file and submitting to AssemblyAI...")
        transcript = transcriber.transcribe(audio_path, config=config)

        if transcript.status == assemblyai.TranscriptStatus.error:
            app.logger.error("Transcription failed: %s", transcript.error)
            return f"ERROR: Transcription failed - {transcript.error}"

        if transcript.status != assemblyai.TranscriptStatus.completed:
            app.logger.warning("Transcription not completed (status: %s)", transcript.status)
            return f"ERROR: Transcription incomplete - Status: {transcript.status}"

        if not transcript.utterances:
            app.logger.warning("Transcript contains no utterances.")
            return transcript.text or "Transcription completed, but no utterances returned."

        # Format transcript into speaker-labeled lines with timestamps
        formatted_lines = []

        for utterance in transcript.utterances:
            start_ms = utterance.start
            start_td = timedelta(milliseconds=start_ms)
            hours, remainder = divmod(start_td.seconds, 3600)
            minutes, seconds = divmod(remainder, 60)
            timestamp = f"{hours:02}:{minutes:02}:{seconds:02}"

            speaker_label = utterance.speaker or "UNKNOWN"
            line = f"[{timestamp}] {speaker_label}: {utterance.text}"
            formatted_lines.append(line)

        transcript_string = "\n".join(formatted_lines)
        app.logger.info("Formatted transcript generated (%d characters).", len(transcript_string))

        if len(transcript_string) < 2000:
            app.logger.debug("Transcript preview:\n%s", transcript_string)
        else:
            app.logger.debug("Transcript preview (first 2000 chars):\n%s", transcript_string[:2000])

        return transcript_string

    except Exception as err:
        app.logger.error("AssemblyAI transcription failed: %s", err, exc_info=True)
        return f"ERROR: Transcription failed - {str(err)}"

# ── Flask Routes ──────────────────────────────────────────────────────────────
# Exposes /api/v1/upload_audio for audio upload + transcription

@app.route("/api/v1/upload_audio", methods=["POST"])
def upload_audio_file_route():
    """Endpoint to upload and transcribe an audio file.

    Accepts multipart/form-data with an 'audioFile' field. The file is saved,
    converted to a 16kHz mono WAV using FFmpeg, and passed to AssemblyAI for
    transcription. The resulting transcript is returned as JSON.

    Returns:
        A JSON response containing success status, filename, and transcript text
        or an appropriate error message.
    """
    if request.method != "POST":
        return jsonify({
            "success": False,
            "error": "Only POST method is allowed.",
            "transcript": None
        }), 405

    if "audioFile" not in request.files:
        return jsonify({
            "success": False,
            "error": "No file part in the request.",
            "transcript": None
        }), 400

    file = request.files["audioFile"]
    if file.filename == "":
        return jsonify({
            "success": False,
            "error": "No selected file.",
            "transcript": None
        }), 400

    if not allowed_file(file.filename):
        return jsonify({
            "success": False,
            "error": "File type not allowed.",
            "transcript": None
        }), 400

    filename = secure_filename(file.filename)
    original_path = os.path.join(app.config["UPLOAD_FOLDER"], filename)
    processed_path = None

    try:
        # Save uploaded file
        file.save(original_path)
        app.logger.info("File saved: %s", original_path)

        # Convert to 16kHz mono WAV
        base, _ = os.path.splitext(original_path)
        processed_path = base + "_processed.wav"

        ffmpeg_cmd = [
            "ffmpeg",
            "-i", original_path,
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            "-y", processed_path
        ]

        app.logger.info("Running FFmpeg: %s", " ".join(ffmpeg_cmd))
        conversion_result = subprocess.run(
            ffmpeg_cmd,
            check=True,
            capture_output=True
        )
        app.logger.debug("FFmpeg STDERR:\n%s", conversion_result.stderr.decode())

        # Transcribe the processed audio
        transcript_result = perform_transcription_with_assemblyai(processed_path)

        if transcript_result.startswith("ERROR:") or transcript_result.startswith("LLM_"):
            return jsonify({
                "success": False,
                "message": "File processed, but transcription failed or was skipped.",
                "filename": filename,
                "transcript": transcript_result
            }), 500

        return jsonify({
            "success": True,
            "message": "File transcribed successfully.",
            "filename": filename,
            "transcript": transcript_result
        }), 200

    except Exception as err:
        app.logger.error("Upload route error: %s", err, exc_info=True)
        return jsonify({
            "success": False,
            "error": f"Error processing file: {str(err)}",
            "transcript": None
        }), 500

    finally:
        # Clean up processed WAV
        if processed_path and os.path.exists(processed_path):
            try:
                os.remove(processed_path)
                app.logger.info("Deleted temp WAV: %s", processed_path)
            except Exception as cleanup_err:
                app.logger.warning("Failed to delete temp WAV: %s", cleanup_err)

        # Optionally clean up original file (commented out)
        # if original_path != processed_path and os.path.exists(original_path):
        #     try:
        #         os.remove(original_path)
        #         app.logger.info("Deleted original upload: %s", original_path)
        #     except Exception as cleanup_err:
        #         app.logger.warning("Failed to delete original upload: %s", cleanup_err)


def configure_symlinks() -> None:
    """Workaround for Hugging Face symlink issues on Windows.

    On Windows, symlinks can fail due to permissions or filesystem type.
    This function monkey-patches `Path.symlink_to` to copy instead of linking.
    It also sets environment flags to disable symlink warnings in HF tools.
    """
    import shutil
    from pathlib import Path

    def safe_symlink_to(self: Path, target: str, target_is_directory: bool = False):
        try:
            if self.exists():
                self.unlink()

            if Path(target).is_file():
                shutil.copyfile(target, self)
            else:
                shutil.copytree(target, self)
        except Exception as err:
            print(f"⚠️ Failed to mimic symlink, copying instead: {err}")

    Path.symlink_to = safe_symlink_to

    os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    os.environ["HF_HUB_DISABLE_SYMLINKS"] = "1"
    os.environ["SPEECHBRAIN_FETCHING_STRATEGY"] = "copy"


if __name__ == "__main__":
    app.logger.info("Bootstrapping DocBud backend...")

    if not ASSEMBLYAI_API_KEY:
        app.logger.critical("ASSEMBLYAI_API_KEY is missing. Transcription will fail.")
    if not OPENAI_API_KEY:
        app.logger.warning("OPENAI_API_KEY is missing. LLM cleanup will be skipped.")

    app.logger.info("Starting Flask development server on http://0.0.0.0:5000")
    app.run(host="0.0.0.0", port=5000, debug=False)
