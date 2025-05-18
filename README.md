# Med Assist [WORK IN PROGRESS]
Note: This is a work in progress and does not represent a finished product

## Overview

Med Assist is a smart medical assistant tool designed to record, transcribe, and eventually summarize medical appointments. The project consists of:

* **An Android mobile application (MedAssist):** Built with Kotlin and Jetpack Compose, allowing users to record new audio or select existing audio files for transcription.
* **A Python backend service:** Intended to handle the audio processing, including transcription with OpenAI Whisper, speaker diarization with PyAnnote, and post-processing with a local LLM via Ollama. (The web API for this service is currently under development).

Future iterations of the tool will allow users to interact with a chat assistant to answer questions about past appointments.

## Current Features

**Android App (MedAssist - Frontend):**

* Record audio directly within the app.
* Select existing audio files from the device's shared storage.

**Python Service (Backend):**

* Core transcription logic using OpenAI Whisper.
* Speaker diarization capabilities using PyAnnote.

## Current Development Focus

* Implementing networking in the Android app to upload audio files.
* Developing the Python backend API service to receive audio, perform transcription/diarization, and return results.
