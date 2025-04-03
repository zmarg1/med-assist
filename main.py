"""Medical appointment transcription with speaker diarization"""

import os
import subprocess
from datetime import timedelta

import sounddevice as sd
import whisper
import yt_dlp
from dotenv import load_dotenv
from pyannote.audio import Pipeline
from scipy.io.wavfile import write


# Constants
WAV_PATH = "./appts-audio/example_appt.wav"
EXAMPLE_AUDIO_FILE = "example_appt.wav"
YOUTUBE_URL = "https://www.youtube.com/watch?v=SerstX6D_CU"


def apply_symlink_patch():
    """Configures environment and symlink behavior to avoid Windows symlink errors."""
    import shutil
    from pathlib import Path

    def safe_symlink_to(self, target, target_is_directory=False):
        """Replace symlink with copy for files and directories (Windows-safe)."""
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

    # Disable symlinks to avoid issues on Windows
    os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    os.environ["HF_HUB_DISABLE_SYMLINKS"] = "1"
    os.environ["SPEECHBRAIN_FETCHING_STRATEGY"] = "copy"


def transcribe_audio():
    """Run speaker diarization and transcription, then save a labeled transcript."""
    print("Loading diarization model...")
    load_dotenv()
    hf_token = os.getenv("HF_TOKEN")
    pipeline = Pipeline.from_pretrained(
        "pyannote/speaker-diarization@2.1",
        use_auth_token=hf_token
    )

    print("Running diarization...")
    diarization = pipeline(WAV_PATH, num_speakers=2)

    print("Running Whisper transcription...")
    whisper_model = whisper.load_model("base")
    whisper_result = whisper_model.transcribe(WAV_PATH, word_timestamps=False)

    for i, segment in enumerate(whisper_result["segments"]):
        segment["id"] = i  # Assign unique IDs for tracking

    final_transcript = []
    used_segments = set()

    # Collect diarization segments with Whisper
    for segment in diarization.itertracks(yield_label=True):
        start = segment[0].start
        end = segment[0].end
        speaker = segment[2]

        matched_text = []
        for s in whisper_result["segments"]:
            if s["start"] >= start and s["end"] <= end and s["id"] not in used_segments:
                matched_text.append(s["text"].strip())
                used_segments.add(s["id"])

        if matched_text:
            combined = " ".join(matched_text)
            timestamp = f"[{str(timedelta(seconds=int(start)))}]"
            final_transcript.append(f"{timestamp} {speaker}: {combined}")

    # Handle early segments not captured in diarization
    diarization_start = next(diarization.itertracks(yield_label=True))[0].start
    early_segments = [
        s for s in whisper_result["segments"]
        if s["start"] < diarization_start and s["id"] not in used_segments
    ]
    if early_segments:
        early_text = " ".join(s["text"].strip() for s in early_segments)
        timestamp = f"[{str(timedelta(seconds=int(early_segments[0]['start'])))}]"
        final_transcript.insert(0, f"{timestamp} SPEAKER_XX: {early_text}")
        print("👂 Prepended early speech segment before diarization window...")

    print("\nSpeaker-labeled Transcript:\n")
    transcript_text = "\n".join(final_transcript)
    print(transcript_text)

    with open("transcript.txt", "w", encoding="utf-8") as f:
        f.write(transcript_text)
        print("\nTranscript saved as transcript.txt ✅")


def download_example_audio():
    """Downloads and trims a YouTube video to a 2-minute WAV audio clip."""
    output_dir = "./appts-audio"
    os.makedirs(output_dir, exist_ok=True)

    temp_base = os.path.join(output_dir, "temp_full_audio")
    temp_filename = f"{temp_base}.wav"
    trimmed_filename = os.path.join(output_dir, EXAMPLE_AUDIO_FILE)

    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": temp_base,
        "postprocessors": [{
            "key": "FFmpegExtractAudio",
            "preferredcodec": "wav",
            "preferredquality": "192",
        }],
        "quiet": False,
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([YOUTUBE_URL])
        print(f"Downloaded full audio to temp file: {temp_filename}")

    subprocess.run([
        "ffmpeg", "-y", "-i", temp_filename,
        "-t", "120",
        "-acodec", "copy", trimmed_filename
    ], check=True)

    print(f"Trimmed audio to first 2 minutes → {EXAMPLE_AUDIO_FILE}")
    os.remove(temp_filename)


if __name__ == "__main__":
    #download_example_audio()
    apply_symlink_patch()
    transcribe_audio()
