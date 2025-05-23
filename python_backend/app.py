import os
import subprocess # For Ollama
import json # For potential caching if we re-add it carefully
from datetime import timedelta # For Ollama input formatting
from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename
from dotenv import load_dotenv
import logging

# --- Hugging Face and ML Model Imports ---
#from pyannote.audio import Pipeline as PyannotePipeline
#from transformers import pipeline as hf_pipeline # For Whisper

# --- AssemblyAI Import ---
import assemblyai

# --- OpenAI Import ---
import openai # New import
from openai import OpenAI # New import for newer SDK style

# --- Initialize Flask App ---
app = Flask(__name__)

# --- Explicitly configure app.logger to show INFO messages ---
app.logger.setLevel(logging.INFO)
if app.debug:
    if not any(isinstance(h, logging.StreamHandler) and h.level <= logging.INFO for h in app.logger.handlers):
        if app.logger.hasHandlers():
            app.logger.handlers.clear()
        info_handler = logging.StreamHandler()
        info_handler.setLevel(logging.INFO)
        # formatter = logging.Formatter('[%(asctime)s] %(levelname)s in %(module)s (%(funcName)s): %(message)s')
        # info_handler.setFormatter(formatter)
        app.logger.addHandler(info_handler)
        app.logger.propagate = False
elif not app.logger.handlers:
    logging.basicConfig(level=logging.WARNING)

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
#HF_TOKEN = os.getenv("HF_TOKEN")
ASSEMBLYAI_API_KEY = os.getenv("ASSEMBLYAI_API_KEY")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")

# --- Configure AssemblyAI ---
if not ASSEMBLYAI_API_KEY:
    app.logger.warning("ASSEMBLYAI_API_KEY not found in .env file. Transcription will fail.")
    # You might want to raise an error or exit if the key is critical
else:
    assemblyai.settings.api_key = ASSEMBLYAI_API_KEY
    app.logger.info("AssemblyAI API key configured.")

# --- Configure OpenAI Client ---
openai_client = None
if not OPENAI_API_KEY:
    app.logger.warning("OPENAI_API_KEY not found in .env file. LLM cleaning will be skipped or will use fallback.")
else:
    try:
        openai_client = OpenAI(api_key=OPENAI_API_KEY) # Newer SDK style
        # openai.api_key = OPENAI_API_KEY # Older SDK style, OpenAI() is preferred
        app.logger.info("OpenAI client configured successfully.")
    except Exception as e:
        app.logger.error(f"Failed to configure OpenAI client: {e}")
# --- End OpenAI Client Config ---

# --- Old Global Model Initializations ---
#DIARIZATION_PIPELINE = None
#ASR_PIPELINE = None
#app.logger.info("Local PyAnnote and Whisper pipelines are disabled; using AssemblyAI.")

"""try:
    app.logger.info("Loading Diarization model...")
    DIARIZATION_PIPELINE = PyannotePipeline.from_pretrained(
        "pyannote/speaker-diarization-3.1",
        use_auth_token=HF_TOKEN
    )
    app.logger.info("Diarization model (3.1) loaded successfully.")

    app.logger.info("Loading Whisper ASR model...")
    ASR_PIPELINE = hf_pipeline(
        "automatic-speech-recognition",
        model="openai/whisper-base",
        chunk_length_s=30,
        stride_length_s=(5, 5),
    )
    app.logger.info("Whisper ASR model loaded successfully.")
except Exception as e:
    app.logger.error(f"Error loading ML models: {e}", exc_info=True) # Added exc_info for more details"""

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
    app.logger.info("Applying light rules (if any)...")
    return cleaned

def clean_transcript_with_openai_llm(diarized_transcript_text: str): # Renamed and modified
    app.logger.info("Attempting to clean transcript with OpenAI LLM (gpt-3.5-turbo)...")
    if not openai_client:
        app.logger.warning("OpenAI client not configured. Skipping LLM cleaning.")
        return f"LLM_SKIPPED: OpenAI client not configured. Original transcript:\n{diarized_transcript_text}"

    system_prompt = (
        "You are an expert in refining diarized medical appointment transcripts. "
        "Your task is to review the provided transcript, which includes timestamps and speaker labels (e.g., A, B). "
        "Make only essential corrections such as fixing obvious misspellings of common medical terms or very obvious grammatical errors that hinder readability. "
        "Do NOT change the meaning, add new information, or remove information. "
        "Do NOT change speaker labels unless there's an extremely obvious error indicated by the dialogue flow (be very conservative). "
        "Do NOT add personal names (like 'Dr. Smith' or patient names like 'Cat') unless they are explicitly and clearly spoken in the transcript. "
        "Ensure the original timestamp and speaker label format ([HH:MM:SS] SPEAKER_LABEL: Text) is preserved for each utterance. "
        "If an utterance appears to be split across lines from the same speaker at the same timestamp, you can merge them. "
        "If an utterance contains speech from two speakers that should be separate turns, try to split them into new lines with appropriate (or estimated if necessary) timestamps and speaker labels. "
        "Focus on clarity and fidelity to the original speech."
    )
    
    user_prompt = (
        "Please clean the following diarized medical transcript according to the rules provided:\n\n"
        f"{diarized_transcript_text}"
    )

    try:
        app.logger.info("Sending transcript to OpenAI API for cleaning...")
        chat_completion = openai_client.chat.completions.create(
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            model="gpt-3.5-turbo", # Or "gpt-4o-mini" for a balance, or "gpt-4o" for best quality (check pricing)
            temperature=0.2, # Lower temperature for more deterministic, less creative output
        )
        
        cleaned_text = chat_completion.choices[0].message.content
        app.logger.info("OpenAI LLM cleaning successful.")
        # Ensure the output is a string, even if it's empty
        return cleaned_text if cleaned_text is not None else ""

    except openai.APIConnectionError as e:
        app.logger.error(f"OpenAI API connection error: {e}")
        return f"LLM_ERROR: OpenAI API connection error. Original transcript:\n{diarized_transcript_text}"
    except openai.RateLimitError as e:
        app.logger.error(f"OpenAI API rate limit exceeded: {e}")
        return f"LLM_ERROR: OpenAI API rate limit exceeded. Original transcript:\n{diarized_transcript_text}"
    except openai.AuthenticationError as e:
        app.logger.error(f"OpenAI API authentication error (check your API key): {e}")
        return f"LLM_ERROR: OpenAI API authentication error. Original transcript:\n{diarized_transcript_text}"
    except openai.APIError as e: # Catch other OpenAI API errors
        app.logger.error(f"OpenAI API error: {e}")
        return f"LLM_ERROR: OpenAI API error ({e.status_code}). Original transcript:\n{diarized_transcript_text}"
    except Exception as e:
        app.logger.error(f"An unexpected error occurred during OpenAI LLM cleaning: {e}", exc_info=True)
        return f"LLM_ERROR: Unexpected error during OpenAI cleaning. Original transcript:\n{diarized_transcript_text}"


def clean_with_local_llm(transcript_text_input): # Modified to take simple text
    app.logger.info("Attempting to clean transcript with local LLM via Ollama...")
    # The prompt might need adjustment if AssemblyAI's diarized format is different
    # For now, let's assume transcript_text_input is already speaker-diarized lines
    prompt = (
        "You are reviewing a diarized medical appointment transcript.\n"
        "Each line has a timestamp, a speaker label, and a spoken utterance.\n"
        "Your job is to:\n"
        "- Fix incorrect speaker labels if obvious (but be conservative).\n"
        "- Do NOT add names or titles like 'Dr.' or 'Cat' unless they are clearly part of the speech.\n"
        "- Do NOT change any words from the original transcription unless it's a very obvious typo fixable from context.\n"
        "- If a line contains responses from both speakers, attempt to split it if feasible.\n"
        "- Ensure timestamps are preserved.\n"
        "- Return only the cleaned transcript, maintaining the original format:\n"
        "  [HH:MM:SS] SPEAKER_LABEL: Utterance text\n"
        "\nOriginal Transcript:\n"
        f"{transcript_text_input}"
    )
    try:
        # (Ollama subprocess call remains the same as before)
        result = subprocess.run(
            ["ollama", "run", "mistral"],
            input=prompt.encode("utf-8"),
            capture_output=True,
            check=True,
            timeout=120
        )
        output = result.stdout.decode("utf-8")
        # Basic filtering for lines that look like transcript lines
        cleaned_lines = [line for line in output.splitlines() if line.strip().startswith("[") and ":" in line.split(":", 1)[1]]
        if not cleaned_lines and output: # If filtering removed everything, return raw Ollama output
             app.logger.warning("LLM output did not match expected format, returning raw output.")
             return "LLM_RAW_OUTPUT:\n" + output
        app.logger.info("LLM cleaning successful (or attempted).")
        return "\n".join(cleaned_lines)
    except Exception as e: # Catching broader exceptions for Ollama call
        app.logger.error(f"Ollama processing failed: {e}", exc_info=True)
        return f"LLM_ERROR: Ollama processing failed. Original transcript:\n{transcript_text_input}"

def perform_transcription_with_assemblyai(audio_path):
    app.logger.info(f"Starting AssemblyAI transcription for: {audio_path}")
    if not ASSEMBLYAI_API_KEY:
        # Return an error structure consistent with what the app expects
        app.logger.error("AssemblyAI API key not configured during transcription attempt.")
        return "ERROR: AssemblyAI API key not configured."


    try:
        transcriber = assemblyai.Transcriber()
        config = assemblyai.TranscriptionConfig(
            speaker_labels=True,
            speech_model=assemblyai.SpeechModel.best
        )

        app.logger.info(f"Uploading {audio_path} to AssemblyAI and submitting for transcription...")
        transcript = transcriber.transcribe(audio_path, config=config)

        if transcript.status == assemblyai.TranscriptStatus.error:
            app.logger.error(f"AssemblyAI transcription error: {transcript.error}")
            return f"ERROR: AssemblyAI transcription failed - {transcript.error}"
        
        if transcript.status != assemblyai.TranscriptStatus.completed:
            app.logger.warning(f"AssemblyAI transcription not completed. Status: {transcript.status}")
            return f"ERROR: AssemblyAI transcription did not complete. Status: {transcript.status}"

        if not transcript.utterances:
            app.logger.warning("AssemblyAI transcription complete but no utterances found.")
            # Return the full text if available, otherwise a message
            return transcript.text if transcript.text else "Transcription complete, but no utterances found by AssemblyAI."

        # Format the transcript with speaker labels and timestamps
        formatted_transcript_lines = []
        for utterance in transcript.utterances:
            start_ms = utterance.start
            start_td = timedelta(milliseconds=start_ms)
            hours, remainder = divmod(start_td.seconds, 3600)
            minutes, seconds = divmod(remainder, 60)
            start_ts_str = f"{hours:02}:{minutes:02}:{seconds:02}"
            
            formatted_transcript_lines.append(
                f"[{start_ts_str}] {utterance.speaker if utterance.speaker else 'UNKNOWN'}: {utterance.text}"
            )
        
        assemblyai_transcript_string = "\n".join(formatted_transcript_lines)
        app.logger.info(f"AssemblyAI transcription successful and formatted. (Length: {len(assemblyai_transcript_string)} chars).")
        if len(assemblyai_transcript_string) < 2000:
             app.logger.info(f"Formatted transcript content from AssemblyAI:\n{assemblyai_transcript_string}")
        else:
            app.logger.info(f"Formatted transcript content from AssemblyAI (first 2000 chars):\n{assemblyai_transcript_string[:2000]}...")
        
        # --- LLM Cleanup Call is Now Removed/Commented Out ---
        # app.logger.info("Passing AssemblyAI transcript to OpenAI LLM for cleanup...")
        # final_cleaned_transcript = clean_transcript_with_openai_llm(assemblyai_transcript_string)
        # app.logger.info("OpenAI LLM cleanup attempt complete.")
        # return final_cleaned_transcript 
        # --- END LLM Cleanup Call ---

        return assemblyai_transcript_string # Return AssemblyAI transcript directly

    except Exception as e:
        app.logger.error(f"Error during AssemblyAI transcription process: {e}", exc_info=True)
        return f"ERROR: AssemblyAI transcription process failed - {str(e)}"
    # The finally block for cleaning up processed_wav_path_for_assembly in upload_audio_file_route
    # should still exist if that's where you put it.



"""def perform_transcription(audio_path_original_upload):
    app.logger.info(f"Starting transcription for original uploaded file: {audio_path_original_upload}")
    if not ASR_PIPELINE:
        app.logger.error("ASR_PIPELINE (Whisper) not loaded. Cannot transcribe.")
        return "ERROR: Whisper model not available."

    # --- FFmpeg Conversion ---
    processed_wav_path = ""
    try:
        base, ext = os.path.splitext(audio_path_original_upload)
        processed_wav_path = base + "_processed.wav"
        ffmpeg_command = ["ffmpeg", "-i", audio_path_original_upload, "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", "-y", processed_wav_path]
        app.logger.info(f"Converting to standardized WAV: {' '.join(ffmpeg_command)}")
        result = subprocess.run(ffmpeg_command, check=True, capture_output=True)
        app.logger.info(f"FFmpeg STDERR for conversion: {result.stderr.decode()}")
        app.logger.info(f"Successfully converted '{audio_path_original_upload}' to '{processed_wav_path}'")
    except Exception as e:
        app.logger.error(f"Audio conversion failed: {e}", exc_info=True)
        return f"ERROR: Audio conversion failed - {str(e)}"
    # --- END CONVERSION ---

    try:
        # 1. Diarization (Re-enabled)
        diarization_segments = []
        if DIARIZATION_PIPELINE:
            try:
                app.logger.info(f"Running diarization on: {processed_wav_path}")
                diarization_result = DIARIZATION_PIPELINE(processed_wav_path)
                for turn, _, speaker in diarization_result.itertracks(yield_label=True):
                    diarization_segments.append({"start": turn.start, "end": turn.end, "speaker": speaker})
                app.logger.info(f"Diarization complete. Found {len(diarization_segments)} segments.")
                if diarization_segments:
                    app.logger.info(f"First diarization segment example: {diarization_segments[0]}")
            except Exception as e_diar:
                app.logger.error(f"Error during diarization: {e_diar}", exc_info=True)
                diarization_segments = [] # Fallback
                # Consider if this should be a fatal error for the request
        else:
            app.logger.warning("Diarization pipeline not available or not loaded. Proceeding without speaker labels.")

        # 2. ASR (Whisper) with CHUNK timestamps
        app.logger.info(f"Running ASR (Whisper) on: {processed_wav_path} with CHUNK timestamps...")
        asr_result = ASR_PIPELINE(processed_wav_path, return_timestamps="chunk")
        
        full_text_transcription = asr_result.get("text", "") # Full text for reference or fallback
        asr_chunks = asr_result.get("chunks", []) # This should now be populated
        
        app.logger.info(f"ASR complete. Raw Whisper output (full text): {full_text_transcription}")
        if asr_chunks:
            app.logger.info(f"ASR first chunk example: {asr_chunks[0]}")
        else:
            app.logger.warning("ASR did not return timestamped chunks. Alignment will be basic.")


         # 3. Align ASR Chunks with Diarization Segments
        app.logger.info("Aligning ASR chunks with diarization segments...")
        aligned_transcript_parts = []
        
        if not asr_chunks and full_text_transcription:
            # Fallback if Whisper gives no chunks but gives full text
            speaker = diarization_segments[0]["speaker"] if diarization_segments else "UNKNOWN_SPEAKER"
            aligned_transcript_parts.append({
                "speaker": speaker,
                "start_time": 0.0, 
                "text": full_text_transcription.strip()
            })
            app.logger.warning("No ASR chunks for alignment, used full text with first/unknown speaker.")
        
        elif asr_chunks: # If we have ASR chunks
            if not diarization_segments:
                app.logger.warning("No diarization segments. Attributing all ASR chunks to UNKNOWN_SPEAKER.")
                for chunk_idx, chunk in enumerate(asr_chunks):
                    chunk_start_time = chunk.get("timestamp", (0.0, None))[0] or 0.0
                    aligned_transcript_parts.append({
                        "speaker": "UNKNOWN_SPEAKER",
                        "start_time": chunk_start_time,
                        "text": chunk["text"].strip()
                    })
            else: # Both ASR chunks and Diarization segments are available
                app.logger.info(f"Aligning {len(asr_chunks)} ASR chunks with {len(diarization_segments)} diarization segments.")
                
                # Get total audio duration for handling None end timestamps better if needed
                # This could come from ffmpeg info, or estimate from last diarization segment.
                # For now, we'll handle None as we did.
                
                for chunk_idx, chunk in enumerate(asr_chunks):
                    chunk_text = chunk["text"].strip()
                    chunk_timestamp = chunk.get("timestamp", (None, None)) # e.g. (start, end)
                    
                    chunk_start = chunk_timestamp[0] if chunk_timestamp and chunk_timestamp[0] is not None else 0.0
                    chunk_end = chunk_timestamp[1] # This can be None for the last chunk

                    # Determine effective end for the current chunk for overlap calculation
                    effective_chunk_end = chunk_end
                    if effective_chunk_end is None: # If current chunk's end is None
                        if chunk_idx + 1 < len(asr_chunks): # Check if there's a next chunk
                            next_chunk_ts = asr_chunks[chunk_idx+1].get("timestamp")
                            if next_chunk_ts and next_chunk_ts[0] is not None:
                                effective_chunk_end = next_chunk_ts[0] # End current chunk where next one starts
                            else: # Next chunk also has issues or no start time
                                effective_chunk_end = chunk_start + 5.0 # Estimate 5s duration
                        else: # This is the very last chunk and its end is None
                            # Estimate based on average word duration or a fixed amount
                            # For a chunk, let's estimate a bit longer.
                            effective_chunk_end = chunk_start + 10.0 # Estimate 10s duration for last chunk with None end
                        app.logger.debug(f"ASR Chunk {chunk_idx} had None end time, estimated to {effective_chunk_end:.2f} for overlap calculation.")
                    
                    if effective_chunk_end <= chunk_start: # Safety check
                        effective_chunk_end = chunk_start + 0.1


                    current_chunk_speaker = "UNKNOWN_SPEAKER" # Reset for each ASR chunk
                    max_overlap_for_this_chunk = 0.0        # Reset for each ASR chunk

                    for dia_seg in diarization_segments:
                        # Calculate overlap: max(0, min(end1, end2) - max(start1, start2))
                        overlap_start_time = max(chunk_start, dia_seg["start"])
                        overlap_end_time = min(effective_chunk_end, dia_seg["end"])
                        overlap_duration = max(0, overlap_end_time - overlap_start_time)

                        if overlap_duration > max_overlap_for_this_chunk:
                            max_overlap_for_this_chunk = overlap_duration
                            current_chunk_speaker = dia_seg["speaker"]
                    
                    # Fallback: if no overlap found, assign based on which segment contains the start of the chunk
                    if current_chunk_speaker == "UNKNOWN_SPEAKER" and max_overlap_for_this_chunk == 0.0:
                        for dia_seg in diarization_segments:
                            if dia_seg["start"] <= chunk_start < dia_seg["end"]:
                                current_chunk_speaker = dia_seg["speaker"]
                                app.logger.debug(f"ASR Chunk {chunk_idx} ('{chunk_text[:20]}...') assigned to {current_chunk_speaker} by start_time containment (no overlap).")
                                break 
                    
                    if max_overlap_for_this_chunk > 0:
                         app.logger.debug(f"ASR Chunk {chunk_idx} ('{chunk_text[:20]}...') assigned to {current_chunk_speaker} with overlap {max_overlap_for_this_chunk:.2f}s.")


                    aligned_transcript_parts.append({
                        "speaker": current_chunk_speaker,
                        "start_time": chunk_start,
                        "text": chunk_text
                    })
        else: 
            app.logger.warning("No ASR chunks available for alignment.")


        # 4. Format the final transcript string
        formatted_transcript_lines = []
        for part in aligned_transcript_parts:
            start_ts_str = str(timedelta(seconds=int(part['start_time'])))
            formatted_transcript_lines.append(f"[{start_ts_str}] {part['speaker']}: {part['text']}")
        
        final_transcript_string = "\n".join(formatted_transcript_lines)
        app.logger.info(f"Formatted aligned transcript (length: {len(final_transcript_string)} chars)")
        if len(final_transcript_string) < 2000: # Avoid logging excessively long transcripts fully
             app.logger.info(f"Formatted aligned transcript content: \n{final_transcript_string}")
        else:
            app.logger.info(f"Formatted aligned transcript content (first 2000 chars): \n{final_transcript_string[:2000]}...")


        # 5. (Ollama LLM cleanup - still disabled)
        # ...

        return final_transcript_string

    except Exception as e:
        app.logger.error(f"Error during transcription ML processing for '{processed_wav_path}': {e}", exc_info=True)
        # ... (rest of error handling) ...
        if "out of memory" in str(e).lower():
            return "ERROR: Transcription failed - Out of memory during ML processing."
        return f"ERROR: Transcription failed during ML processing - {str(e)}"
    finally:
        # Clean up the processed WAV file
        if processed_wav_path and os.path.exists(processed_wav_path):
            try:
                os.remove(processed_wav_path)
                app.logger.info(f"Cleaned up temporary processed WAV file: {processed_wav_path}")
            except Exception as e_remove:
                app.logger.error(f"Error removing temporary processed WAV file {processed_wav_path}: {e_remove}")
"""

def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

@app.route('/api/v1/upload_audio', methods=['POST'])
def upload_audio_file_route():
    # ... (file checking, ffmpeg conversion to processed_wav_path_for_assembly) ...
    # This part remains the same as your current working version for saving and converting.
    # Ensure 'processed_wav_path_for_assembly' is what you pass to the transcription function.
    if request.method == 'POST':
        if 'audioFile' not in request.files:
            return jsonify({"success": False, "error": "No file part in the request", "transcript": None}), 400
        file = request.files['audioFile']
        if file.filename == '':
            return jsonify({"success": False, "error": "No selected file", "transcript": None}), 400

        if file and allowed_file(file.filename):
            filename = secure_filename(file.filename)
            original_save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            processed_wav_path = None # Initialize
            try:
                file.save(original_save_path)
                app.logger.info(f"File '{filename}' saved successfully to '{original_save_path}'")

                base, ext = os.path.splitext(original_save_path)
                processed_wav_path = base + "_processed.wav"
                ffmpeg_command = [
                    "ffmpeg", "-i", original_save_path,
                    "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
                    "-y", processed_wav_path
                ]
                app.logger.info(f"Converting '{original_save_path}' to standardized WAV: {' '.join(ffmpeg_command)}")
                conversion_result = subprocess.run(ffmpeg_command, check=True, capture_output=True)
                app.logger.info(f"FFmpeg conversion successful. STDERR: {conversion_result.stderr.decode()}")
                
                transcript_result = perform_transcription_with_assemblyai(processed_wav_path)
                
                if "ERROR:" in transcript_result or "LLM_ERROR:" in transcript_result or "LLM_SKIPPED:" in transcript_result:
                    return jsonify({
                        "success": False, 
                        "message": "File processed, but transcription/cleanup failed or was skipped.",
                        "filename": filename,
                        "transcript": transcript_result 
                    }), 500
                else:
                    return jsonify({
                        "success": True,
                        "message": "File transcribed and cleaned successfully.",
                        "filename": filename,
                        "transcript": transcript_result
                    }), 200
            except Exception as e:
                app.logger.error(f"Error in upload route: {e}", exc_info=True)
                return jsonify({"success": False, "error": f"Error processing file: {str(e)}", "transcript": None}), 500
            finally:
                if processed_wav_path and os.path.exists(processed_wav_path):
                    try: os.remove(processed_wav_path)
                    except Exception as e_rem: app.logger.error(f"Error removing processed WAV: {e_rem}")
                # Optionally delete original_save_path if it's different from processed_wav_path and not needed
                # if original_save_path != processed_wav_path and os.path.exists(original_save_path):
                #    try: os.remove(original_save_path)
                #    except Exception as e_rem: app.logger.error(f"Error removing original upload: {e_rem}")
        else:
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
    app.logger.info("Checking API key configurations...")
    if not ASSEMBLYAI_API_KEY:
        app.logger.critical("ASSEMBLYAI_API_KEY IS MISSING.")
    if not OPENAI_API_KEY:
        app.logger.warning("OPENAI_API_KEY IS MISSING. LLM cleaning will be skipped.")
    app.logger.info("Starting Flask server...")
    app.run(host='0.0.0.0', port=5000, debug=False)
