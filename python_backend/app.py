import os
from flask import Flask, request, jsonify
from werkzeug.utils import secure_filename

# Initialize the Flask application
app = Flask(__name__)

# Configuration
UPLOAD_FOLDER = 'uploads'  # Directory where uploaded files will be stored
ALLOWED_EXTENSIONS = {'mp4', 'wav', 'm4a', 'mp3', '3gp'} # Define allowed audio file extensions

app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

# Helper function to check for allowed file extensions
def allowed_file(filename):
    return '.' in filename and \
           filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

# Ensure the upload folder exists
if not os.path.exists(UPLOAD_FOLDER):
    os.makedirs(UPLOAD_FOLDER)
    print(f"Created upload folder at: {os.path.abspath(UPLOAD_FOLDER)}")
else:
    print(f"Upload folder already exists at: {os.path.abspath(UPLOAD_FOLDER)}")


@app.route('/api/v1/upload_audio', methods=['POST'])
def upload_audio_file():
    if request.method == 'POST':
        # Check if the post request has the file part
        if 'audioFile' not in request.files:
            app.logger.error('No file part in the request')
            return jsonify({"error": "No file part in the request"}), 400
        
        file = request.files['audioFile']
        
        # If the user does not select a file, the browser submits an
        # empty file without a filename.
        if file.filename == '':
            app.logger.error('No selected file')
            return jsonify({"error": "No selected file"}), 400
        
        if file and allowed_file(file.filename):
            filename = secure_filename(file.filename) # Secure the filename
            save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            try:
                file.save(save_path)
                app.logger.info(f"File '{filename}' saved successfully to '{save_path}'")
                
                # For now, just return a success message and the filename.
                # Later, this is where you would trigger your transcription script.
                return jsonify({
                    "success": True,
                    "message": "File received and saved successfully.",
                    "filename": filename,
                    # "transcript": "Transcription will go here..." # Placeholder for later
                }), 200
            except Exception as e:
                app.logger.error(f"Error saving file: {e}")
                return jsonify({"error": f"Error saving file: {str(e)}"}), 500
        else:
            app.logger.error(f"File type not allowed: {file.filename}")
            return jsonify({"error": "File type not allowed"}), 400
            
    return jsonify({"error": "Only POST method is allowed"}), 405


if __name__ == '__main__':
    # Run the Flask development server
    # host='0.0.0.0' makes the server accessible from any IP address on your network
    # (including 10.0.2.2 from the Android emulator)
    # debug=True enables the debugger and auto-reloader during development
    print("Starting Flask server...")
    app.run(host='0.0.0.0', port=5000, debug=True)