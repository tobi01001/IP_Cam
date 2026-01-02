#!/bin/bash
# Test script to verify version information in /status endpoint
# Usage: ./test_version_endpoint.sh [DEVICE_IP] [PORT]
#
# Example: ./test_version_endpoint.sh 192.168.1.100 8080

DEVICE_IP=${1:-localhost}
PORT=${2:-8080}
URL="http://${DEVICE_IP}:${PORT}/status"

echo "Testing version information at: $URL"
echo "================================================"
echo ""

# Make request and pretty-print JSON
response=$(curl -s "$URL" 2>&1)
exit_code=$?

if [ $exit_code -ne 0 ]; then
    echo "❌ ERROR: Failed to connect to $URL"
    echo "Make sure:"
    echo "  1. The IP Camera server is running"
    echo "  2. The device IP address is correct"
    echo "  3. Port $PORT is accessible"
    exit 1
fi

# Check if response contains version object
if echo "$response" | grep -q '"version"'; then
    echo "✓ Version information found in response"
    echo ""
    echo "Full response:"
    echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
    echo ""
    echo "================================================"
    echo "Version details:"
    echo "================================================"
    echo "$response" | python3 -c "import sys, json; data = json.load(sys.stdin); v = data.get('version', {}); print(f\"Version Name: {v.get('versionName', 'N/A')}\"); print(f\"Version Code: {v.get('versionCode', 'N/A')}\"); print(f\"Branch: {v.get('branch', 'N/A')}\"); print(f\"Commit Hash: {v.get('commitHash', 'N/A')}\"); print(f\"Build Number: {v.get('buildNumber', 'N/A')}\"); print(f\"Build Timestamp: {v.get('buildTimestamp', 'N/A')}\");" 2>/dev/null
    echo ""
    echo "✅ SUCCESS: Version system is working correctly!"
else
    echo "❌ ERROR: Version information not found in response"
    echo "Response:"
    echo "$response"
    exit 1
fi
