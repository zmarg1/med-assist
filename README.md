# DocBud [WORK IN PROGRESS]
Note: This is a work in progress and does not represent a finished product

## Overview

DocBud is a smart medical assistant tool designed to record, transcribe, and summarize medical appointments. The project consists of:

* **An Android mobile application (MedAssist):** Built with Kotlin and Jetpack Compose, allowing users to record new audio or select existing audio files for transcription.
* **A Python backend service:** Intended to handle the audio processing, including transcription with AssemblyAI and post-processing with an LLM. 

Future iterations of the tool will allow users to interact with a chat assistant to answer questions about past appointments.

## Current Features

**Android App (MedAssist - Frontend):**

* Record audio directly within the app.

**Python Service (Backend):**

* Core transcription and diarization using AssemblyAI.

## Current Development Focus

* Implementing networking in the Android app to upload audio files.
* Developing the Python backend API service to receive audio, perform transcription/diarization, and return results.
