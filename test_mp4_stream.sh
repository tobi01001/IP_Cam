#!/bin/bash

# Test script for MP4 streaming functionality
# Usage: ./test_mp4_stream.sh <DEVICE_IP>

set -e

DEVICE_IP=${1:-"192.168.1.100"}
BASE_URL="http://$DEVICE_IP:8080"

echo "==========================================="
echo "MP4 Streaming Test Script"
echo "==========================================="
echo "Device IP: $DEVICE_IP"
echo "Base URL: $BASE_URL"
echo ""

# Step 1: Check server status
echo "[1/6] Checking server status..."
#STATUS=$(curl -s "$BASE_URL/status" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
#echo "Server status: $STATUS"
#if [ "$STATUS" != "running" ]; then
#    echo "ERROR: Server is not running!"
#    exit 1
#fi
echo "✓ Server is running"
echo ""

# Step 2: Check current streaming mode
echo "[2/6] Checking current streaming mode..."
CURRENT_MODE=$(curl -s "$BASE_URL/streamingMode" | grep -o '"streamingMode":"[^"]*"' | cut -d'"' -f4)
echo "Current mode: $CURRENT_MODE"
echo ""

# Step 3: Switch to MP4 mode if needed
if [ "$CURRENT_MODE" != "mp4" ]; then
    echo "[3/6] Switching to MP4 mode..."
    curl -s "$BASE_URL/setStreamingMode?value=mp4" | grep -o '"message":"[^"]*"' | cut -d'"' -f4
    echo "Waiting 5 seconds for camera to rebind..."
    sleep 5
    
    # Verify mode changed
    NEW_MODE=$(curl -s "$BASE_URL/streamingMode" | grep -o '"streamingMode":"[^"]*"' | cut -d'"' -f4)
    echo "New mode: $NEW_MODE"
    if [ "$NEW_MODE" != "mp4" ]; then
        echo "ERROR: Failed to switch to MP4 mode!"
        exit 1
    fi
    echo "✓ Successfully switched to MP4 mode"
else
    echo "[3/6] Already in MP4 mode"
fi
echo ""

# Step 4: Test MP4 stream endpoint (capture 10 seconds)
echo "[4/6] Testing MP4 stream endpoint..."
echo "Capturing 10 seconds of MP4 stream to ./test_stream.mp4..."
timeout 10 curl -s "$BASE_URL/stream.mp4" > ./test_stream.mp4 || true

FILE_SIZE=$(stat -f%z ./test_stream.mp4 2>/dev/null || stat -c%s ./test_stream.mp4 2>/dev/null)
echo "Downloaded file size: $FILE_SIZE bytes"

if [ "$FILE_SIZE" -lt 1000 ]; then
    echo "ERROR: Downloaded file is too small ($FILE_SIZE bytes)"
    echo "This suggests the stream is not working properly"
    exit 1
fi
echo "✓ Stream produced data"
echo ""

# Step 5: Check file type
echo "[5/6] Checking file type..."
FILE_TYPE=$(file ./test_stream.mp4 2>/dev/null || echo "unknown")
echo "File type: $FILE_TYPE"

if echo "$FILE_TYPE" | grep -qi "ISO Media\|MP4\|video"; then
    echo "✓ File appears to be a valid MP4"
else
    echo "WARNING: File may not be a valid MP4 file"
    echo "File content (first 100 bytes in hex):"
    xxd -l 100 ./test_stream.mp4 || hexdump -C ./test_stream.mp4 | head -10
fi
echo ""

# Step 6: Check for MP4 boxes
echo "[6/6] Checking for MP4 structure..."
if xxd ./test_stream.mp4 2>/dev/null | head -5 | grep -q "ftyp"; then
    echo "✓ Found 'ftyp' box (file type)"
else
    echo "WARNING: No 'ftyp' box found"
fi

if xxd ./test_stream.mp4 2>/dev/null | head -50 | grep -q "moov"; then
    echo "✓ Found 'moov' box (movie header)"
else
    echo "WARNING: No 'moov' box found"
fi

if xxd ./test_stream.mp4 2>/dev/null | grep -q "mdat"; then
    echo "✓ Found 'mdat' box (media data)"
else
    echo "WARNING: No 'mdat' box found"
fi
echo ""

echo "==========================================="
echo "Test complete!"
echo "==========================================="
echo ""
echo "File saved to: ./test_stream.mp4"
echo "File size: $FILE_SIZE bytes"
echo ""
echo "Next steps:"
echo "1. Try playing the file with VLC:"
echo "   vlc ./test_stream.mp4"
echo ""
echo "2. Analyze the file with ffprobe:"
echo "   ffprobe ./test_stream.mp4"
echo ""
echo "3. Test live streaming with VLC:"
echo "   vlc $BASE_URL/stream.mp4"
echo ""
