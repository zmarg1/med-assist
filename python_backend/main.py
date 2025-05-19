"""Medical appointment transcription with speaker diarization using Whisper, PyAnnote, and local post-processing."""

import os
import subprocess
import json
from datetime import timedelta
from dotenv import load_dotenv
from pyannote.audio import Pipeline as PyannotePipeline
from transformers import pipeline as hf_pipeline
import sounddevice as sd
from scipy.io.wavfile import write
import yt_dlp
import requests


# Constants
WAV_PATH = "./appts-audio/example_appt.wav"
YOUTUBE_URL = "https://www.youtube.com/watch?v=SerstX6D_CU"
DIARIZATION_CACHE = "diarization.json"
ASR_CACHE = "asr_chunks.json"


def apply_light_rules(text_segments):
    """Apply light rule-based fixes like speaker switch on common phrases."""
    cleaned = []
    for seg in text_segments:
        text = seg["text"].strip()
        if text.lower().startswith("yeah") or text.lower().startswith("no"):
            seg["speaker_override"] = True
        cleaned.append(seg)
    return cleaned


def clean_with_local_llm(aligned_segments):
    """Use a local Mistral model via Ollama to clean up diarized transcript with strict prompt."""
    import subprocess
    from datetime import timedelta

    # Construct the input transcript text with timestamps and speaker labels
    input_text = "".join(
        f"[{str(timedelta(seconds=int(s['start'])))}] {s['speaker']}: {s['text']}\n"
        for s in aligned_segments
    )

    # Safer prompt that discourages hallucination
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
        f"{input_text}"
    )


    # Call Mistral via Ollama
    result = subprocess.run(
        ["ollama", "run", "mistral"],
        input=prompt.encode("utf-8"),
        capture_output=True,
        check=True
    )

    output = result.stdout.decode("utf-8")

    # Only keep cleaned lines that match the expected output pattern
    cleaned_lines = [
        line for line in output.splitlines()
        if line.strip().startswith("[") and ":" in line
    ]

    return cleaned_lines


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


def record_audio(filename="appointment.wav", duration=30, fs=44100):
    print("Recording... Speak now.")
    audio = sd.rec(int(duration * fs), samplerate=fs, channels=1)
    sd.wait()
    write(filename, fs, audio)
    print(f"Recording saved as {filename}")


def transcribe_audio():
    print("Loading diarization model...")
    load_dotenv()
    hf_token = os.getenv("HF_TOKEN")
    diarization_pipeline = PyannotePipeline.from_pretrained(
        "pyannote/speaker-diarization@2.1", use_auth_token=hf_token
    )

    if os.path.exists(DIARIZATION_CACHE):
        print("Loading cached diarization result...")
        with open(DIARIZATION_CACHE, "r", encoding="utf-8") as f:
            diarization_segments = json.load(f)
    else:
        print("Running diarization...")
        diarization_result = diarization_pipeline(WAV_PATH)
        diarization_segments = [
            {"start": turn.start, "end": turn.end, "speaker": speaker}
            for turn, _, speaker in diarization_result.itertracks(yield_label=True)
        ]
        with open(DIARIZATION_CACHE, "w", encoding="utf-8") as f:
            json.dump(diarization_segments, f, indent=2)

    print("Loading Whisper model via Hugging Face pipeline...")
    asr_pipeline = hf_pipeline(
        "automatic-speech-recognition",
        model="openai/whisper-base",
        chunk_length_s=30,
        stride_length_s=(5, 5),
        return_timestamps=True,
        generate_kwargs={"max_new_tokens": 400},
        device=-1,
    )

    if os.path.exists(ASR_CACHE):
        print("Loading cached ASR transcription...")
        with open(ASR_CACHE, "r", encoding="utf-8") as f:
            chunks = json.load(f)
    else:
        print("Running transcription...")
        asr_result = asr_pipeline(WAV_PATH)
        chunks = apply_light_rules(asr_result["chunks"])
        with open(ASR_CACHE, "w", encoding="utf-8") as f:
            json.dump(chunks, f, indent=2)

    print("Aligning speakers to transcript...")
    combined = []
    for chunk in chunks:
        start, end = chunk["timestamp"]
        text = chunk["text"]
        speaker = "unknown"
        for seg in diarization_segments:
            if seg["start"] <= start < seg["end"] or seg["start"] < end <= seg["end"]:
                speaker = seg["speaker"]
                break
        if chunk.get("speaker_override"):
            speaker = "SPEAKER_01" if speaker == "SPEAKER_00" else "SPEAKER_00"
        combined.append({"speaker": speaker, "start": start, "end": end, "text": text})

    print("Cleaning transcript with local LLM...")
    cleaned_transcript = clean_with_local_llm(combined)

    print("\nSpeaker-labeled Transcript:\n")
    print("\n".join(cleaned_transcript))

    with open("transcript.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(cleaned_transcript))
        print("\nTranscript saved as transcript.txt ✅")


if __name__ == "__main__":
    configure_symlinks()
    transcribe_audio()