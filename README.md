# DocBud
*Note: This is an active development project and not yet production-ready.*

## Overview

DocBud is a intelligent mobile assistant designed to record, transcribe, and summarize medical appointments. 
It aims to make healthcare conversations more accessible for users.

The system is split into two main components:

- **Android Frontend:** A mobile app that allows users to:
  - Record new audio from doctor visits.
  - View transcriptions.
  - Download or share transcripts as PDFs.

- **Python Backend:** A Flask-based API service that:
  - Accepts audio file uploads.
  - Uses **AssemblyAI** for transcription and speaker diarization.
  - Enhances transcription using a local or cloud-based **LLM** (Planned for Development).

> Future versions will include a natural-language chat interface for reviewing and asking questions about past appointments.


## In Progress

- Improve audio playback functionality
- Improve error handling
- Integrate an LLM to cleanup transcripts
- Add appointment summaries logic