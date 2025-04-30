# Use an official Python image as the base
FROM python:3.10-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .
COPY users.json .
COPY static/ static/
COPY templates/ templates/

# Expose the port Flask runs on
EXPOSE 8000

# Run the Flask app
CMD ["python", "app.py"]
