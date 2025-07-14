# DocBud
*Note: This is an personal project and not intended for public release.*

## Overview

DocBud is an intelligent mobile assistant designed to record, transcribe, and summarize medical appointments. 
It aims to make healthcare conversations more accessible for users.

The system is split into two main components:

- **Android Frontend:** A mobile app that allows users to:
  - Record new audio from doctor visits
  - View transcriptions
  - Download or share transcripts as PDFs

- **Python Backend:** A Flask-based API service that:
  - Accepts audio file uploads
  - Uses **AssemblyAI** for transcription and speaker diarization

## Future Steps

- Improve diarization accuracy via rule-based logic and LLM cleanup
- Add appointment summarization functionality
- Improve the UI
- Replace AssemblyAI with **OpenAI Whisper**/**Ollama** and **PyAnnote**  for improved:
  - Cost control
  - Data privacy
  - Customization
  - Performance