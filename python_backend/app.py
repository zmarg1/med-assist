import os
import subprocess # For Ollama
import json # For potential caching if we re-add it carefully
from datetime import timedelta # For Ollama input formatting
from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename
from dotenv import load_dotenv
import logging

# --- Hugging Face and ML Model Imports ---
from pyannote.audio import Pipeline as PyannotePipeline
from transformers import pipeline as hf_pipeline # For Whisper

# --- Initialize Flask App ---
app = Flask(__name__)

# --- Explicitly configure app.logger to show INFO messages ---
app.logger.setLevel(logging.INFO) # Ensure the logger itself allows INFO
if app.debug:
    # For debug mode, let's ensure a handler is outputting INFO to the console
    # This assumes Flask's default StreamHandler or adds one if none exists at INFO level
    if not any(isinstance(h, logging.StreamHandler) and h.level <= logging.INFO for h in app.logger.handlers):
        # If no suitable stream handler exists, add one
        stream_handler = logging.StreamHandler()
        stream_handler.setLevel(logging.INFO)
        app.logger.addHandler(stream_handler)
        # If there are existing handlers but they are not showing INFO, this new one will.
        # Alternatively, you could try to find and modify existing Flask/Werkzeug handlers,
        # but that's more complex.

# --- Configuration ---
UPLOAD_FOLDER = 'uploads'
ALLOWED_EXTENSIONS = {'mp4', 'wav', 'm4a', 'mp3', '3gp', 'aac'} # Added aac
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)
    app.logger.info(f"Created upload folder at: {os.path.abspath(UPLOAD_FOLDER)}")
else:
    app.logger.info(f"Upload folder already exists at: {os.path.abspath(UPLOAD_FOLDER)}")

# --- Load Environment Variables (for Hugging Face Token) ---
load_dotenv()
HF_TOKEN = os.getenv("HF_TOKEN")

# --- Initialize Models Globally (Load once when Flask app starts) ---
DIARIZATION_PIPELINE = None
ASR_PIPELINE = None

try:
    app.logger.info("Loading Diarization model...")
    DIARIZATION_PIPELINE = PyannotePipeline.from_pretrained(
        #"pyannote/speaker-diarization- семьи@2.1", # Ensure correct model name if this was a typo
        "pyannote/speaker-diarization@2.1", # Common correct name
        use_auth_token=HF_TOKEN
    )
    app.logger.info("Diarization model loaded successfully.")

    app.logger.info("Loading Whisper ASR model...")
    ASR_PIPELINE = hf_pipeline(
        "automatic-speech-recognition",
        model="openai/whisper-base", # Or your preferred whisper model
        chunk_length_s=30,
        stride_length_s=(5, 5),
        # device=-1, # For CPU. Specify device if using GPU e.g. device=0 for cuda:0
    )
    app.logger.info("Whisper ASR model loaded successfully.")
except Exception as e:
    app.logger.error(f"Error loading ML models: {e}")
    # Depending on severity, you might want to exit or handle this gracefully
    # For now, the app will run but transcription will fail if models aren't loaded.

# --- Helper Functions from your original script (adapted) ---
def apply_light_rules(text_segments): # Assuming this function exists as you defined
    """Apply light rule-based fixes like speaker switch on common phrases."""
    cleaned = []
    for seg in text_segments:
        text = seg["text"].strip()
        if text.lower().startswith("yeah") or text.lower().startswith("no"):
            # This logic might need speaker context from diarization to be truly effective
            seg["speaker_override"] = True # Example, adapt as needed
        cleaned.append(seg)
    return cleaned

def clean_with_local_llm(aligned_segments_text_input):
    """Use a local Mistral model via Ollama to clean up diarized transcript."""
    app.logger.info("Attempting to clean transcript with local LLM via Ollama...")
    prompt = (
        "You are reviewing a diarized medical appointment transcript.\n"
        "Each line has a timestamp, a speaker label, and a spoken utterance.\n"
        "Your job is to:\n"
        "- Fix incorrect speaker labels if obvious.\n"
        "- Do NOT add names or titles like 'Dr.' or 'Cat'.\n"
        "- Do NOT change any words from the original.\n"
        "- If a line contains responses from both speakers, split it.\n"
        "- Return only the cleaned transcript, in this format:\n"
        "  [HH:MM:SS] SPEAKER_X: Original text\n"
        "\nTranscript:\n"
        f"{aligned_segments_text_input}"
    )
    try:
        result = subprocess.run(
            ["ollama", "run", "mistral"], # Ensure 'mistral' model is pulled in Ollama
            input=prompt.encode("utf-8"),
            capture_output=True,
            check=True,
            timeout=120 # Add a timeout (e.g., 2 minutes)
        )
        output = result.stdout.decode("utf-8")
        cleaned_lines = [
            line for line in output.splitlines()
            if line.strip().startswith("[") and ":" in line
        ]
        app.logger.info("LLM cleaning successful.")
        return "\n".join(cleaned_lines) # Return as a single string
    except FileNotFoundError:
        app.logger.error("Ollama command not found. Ensure Ollama is installed and in PATH.")
        return "LLM_ERROR: Ollama not found. Raw aligned text:\n" + aligned_segments_text_input
    except subprocess.CalledProcessError as e:
        app.logger.error(f"Ollama execution failed: {e.stderr.decode('utf-8')}")
        return f"LLM_ERROR: Ollama execution failed. Raw aligned text:\n{aligned_segments_text_input}"
    except subprocess.TimeoutExpired:
        app.logger.error("Ollama execution timed out.")
        return "LLM_ERROR: Ollama execution timed out. Raw aligned text:\n" + aligned_segments_text_input
    except Exception as e:
        app.logger.error(f"An unexpected error occurred during LLM cleaning: {e}")
        return f"LLM_ERROR: Unexpected error during cleaning. Raw aligned text:\n{aligned_segments_text_input}"

def perform_transcription(audio_path_original_mp4):
    app.logger.info(f"Starting transcription for original file: {audio_path_original_mp4}")
    # --- Check if models are loaded ---
    if not ASR_PIPELINE:
        app.logger.error("ASR_PIPELINE (Whisper) not loaded. Cannot transcribe.")
        return "ERROR: Whisper model not available."
    # No need to check DIARIZATION_PIPELINE here if we want to allow fallback if it fails to load
    
    # --- Convert MP4 to WAV (keep this part) ---
    audio_path_for_processing = ""
    # ... (ffmpeg conversion code remains exactly the same as the last version) ...
    try:
        base, ext = os.path.splitext(audio_path_original_mp4)
        wav_path = base + ".wav"
        ffmpeg_command = [
            "ffmpeg", "-i", audio_path_original_mp4,
            "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
            "-y", wav_path
        ]
        app.logger.info(f"Converting to WAV: {' '.join(ffmpeg_command)}")
        result = subprocess.run(ffmpeg_command, check=True, capture_output=True)
        app.logger.info(f"FFmpeg STDERR for conversion: {result.stderr.decode()}")
        app.logger.info(f"Successfully converted '{audio_path_original_mp4}' to '{wav_path}'")
        audio_path_for_processing = wav_path
    except Exception as e:
        app.logger.error(f"Audio conversion failed: {e}", exc_info=True)
        return f"ERROR: Audio conversion failed - {str(e)}"

    if not audio_path_for_processing:
        return "ERROR: Audio processing could not proceed after conversion attempt."
    # --- END CONVERSION ---

    try:
        diarization_segments = [] # Initialize
        if DIARIZATION_PIPELINE: # Only run if diarization model loaded
            try:
                # 1. Diarization
                app.logger.info(f"Running diarization on: {audio_path_for_processing}")
                diarization_result = DIARIZATION_PIPELINE(audio_path_for_processing)
                diarization_segments = [
                    {"start": turn.start, "end": turn.end, "speaker": speaker}
                    for turn, _, speaker in diarization_result.itertracks(yield_label=True)
                ]
                app.logger.info(f"Diarization complete. Found {len(diarization_segments)} segments.")
            except Exception as e_diar:
                app.logger.error(f"Error during diarization: {e_diar}", exc_info=True)
                diarization_segments = [] # Ensure it's empty on failure
                # We can decide if this is a fatal error or if we proceed with ASR only
                # For now, let's log and proceed, ASR will run on the whole file.
        else:
            app.logger.warning("Diarization pipeline not available. Proceeding with ASR only.")

        # 2. ASR (Whisper)
        app.logger.info(f"Running ASR (Whisper) on: {audio_path_for_processing}")
        # Consider adding generate_kwargs={"language": "english"} if your audio is always English
        full_text_transcription = ASR_PIPELINE(audio_path_for_processing)["text"]
        app.logger.info(f"ASR complete. Raw Whisper output: {full_text_transcription}")
        
        # --- SIMPLIFIED COMBINING / LLM INPUT PREPARATION (Ollama still disabled) ---
        # This is where you'd re-implement your more sophisticated alignment if diarization is successful
        # For now, we'll just return the raw ASR text if diarization fails or isn't used,
        # or a very basic speaker-attributed string if diarization worked.
        
        if diarization_segments:
            # Example of creating a more structured (but still simple) text if diarization worked
            # You would replace this with your logic to iterate ASR chunks against diarization_segments
            combined_text_for_now = []
            for seg in diarization_segments:
                # This is a placeholder: ideally, you'd run ASR on segments defined by diarization
                # or align word timestamps from full ASR with speaker turns.
                # For now, we'll just attribute parts of the full transcript conceptually or just list speakers.
                # This simplification won't correctly attribute text parts to speakers yet.
                combined_text_for_now.append(f"[{str(timedelta(seconds=int(seg['start'])))}] {seg['speaker']}: (Speech segment from {seg['start']:.2f}s to {seg['end']:.2f}s)")
            
            # Append the full ASR text below these conceptual segments for now
            combined_text_for_now.append(f"\nFull ASR: {full_text_transcription}")
            final_transcript = "\n".join(combined_text_for_now)
            app.logger.info("Diarization segments found, created conceptual combined text.")
        else:
            final_transcript = full_text_transcription # Fallback to raw ASR if no diarization
            app.logger.info("No diarization segments, using raw ASR output.")

        # Ollama is still disabled:
        # app.logger.info("Preparing text for LLM cleanup...")
        # final_transcript = clean_with_local_llm(simplified_llm_input_text_based_on_alignment)
        
        # Optional: Clean up the WAV file
        # try:
        #     if os.path.exists(audio_path_for_processing):
        #         os.remove(audio_path_for_processing)
        #         app.logger.info(f"Cleaned up temporary WAV file: {audio_path_for_processing}")
        # except Exception as e_remove:
        #     app.logger.error(f"Error removing WAV file {audio_path_for_processing}: {e_remove}")

        return final_transcript

    except Exception as e:
        app.logger.error(f"Error during transcription process (after conversion): {e}", exc_info=True)
        if "out of memory" in str(e).lower():
            return "ERROR: Transcription failed - Out of memory."
        return f"ERROR: Transcription failed - {str(e)}"

def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@app.route('/api/v1/upload_audio', methods=['POST'])
def upload_audio_file_route(): # Renamed to avoid conflict with any potential local var
    if request.method == 'POST':
        if 'audioFile' not in request.files:
            app.logger.error('No file part in the request')
            return jsonify({"success": False, "error": "No file part in the request", "transcript": None}), 400
        
        file = request.files['audioFile']
        
        if file.filename == '':
            app.logger.error('No selected file')
            return jsonify({"success": False, "error": "No selected file", "transcript": None}), 400
        
        if file and allowed_file(file.filename):
            filename = secure_filename(file.filename)
            save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            try:
                file.save(save_path)
                app.logger.info(f"File '{filename}' saved successfully to '{save_path}'")
                
                # --- Perform Transcription ---
                transcript_result = perform_transcription(save_path)
                
                if "ERROR:" in transcript_result or "LLM_ERROR:" in transcript_result :
                    return jsonify({
                        "success": False, 
                        "message": "File processed, but transcription/cleanup failed.",
                        "filename": filename,
                        "transcript": transcript_result # Contains the error message
                    }), 500 # Internal server error
                else:
                    return jsonify({
                        "success": True,
                        "message": "File transcribed successfully.",
                        "filename": filename,
                        "transcript": transcript_result # The actual transcript string
                    }), 200
            except Exception as e:
                app.logger.error(f"Error saving or processing file: {e}", exc_info=True)
                return jsonify({"success": False, "error": f"Error saving or processing file: {str(e)}", "transcript": None}), 500
        else:
            app.logger.error(f"File type not allowed: {file.filename}")
            return jsonify({"success": False, "error": "File type not allowed", "transcript": None}), 400
            
    return jsonify({"error": "Only POST method is allowed", "transcript": None}), 405

def configure_symlinks():
    """Configure symlink workaround for Windows."""
    import shutil
    from pathlib import Path

    def safe_symlink_to(self, target, target_is_directory=False):
        try:
            if self.exists():
                self.unlink()
            if Path(target).is_file():
                shutil.copyfile(target, self)
            else:
                shutil.copytree(target, self)
        except Exception as e:
            print(f"⚠️ Failed to mimic symlink, copying instead. {e}")

    Path.symlink_to = safe_symlink_to
    os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    os.environ["HF_HUB_DISABLE_SYMLINKS"] = "1"
    os.environ["SPEECHBRAIN_FETCHING_STRATEGY"] = "copy"

if __name__ == '__main__':
    app.logger.info("Ensuring Ollama is running and mistral model is available if using LLM cleanup...")
    # Note: Configure symlinks might be needed if running on Windows and HF Hub acts up
    # from your original script: configure_symlinks() # Call this if needed
    configure_symlinks()
    app.logger.info("Starting Flask server...")
    app.run(host='0.0.0.0', port=5000, debug=True)