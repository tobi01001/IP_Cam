#!/bin/bash
# Test script to verify RTSP server reliability with rapid start/stop cycles
# Usage: ./test_rtsp_reliability.sh <DEVICE_IP>

if [ -z "$1" ]; then
    echo "Usage: $0 <DEVICE_IP>"
    echo "Example: $0 192.168.1.100"
    exit 1
fi

DEVICE_IP=$1
BASE_URL="http://$DEVICE_IP:8080"
RTSP_URL="rtsp://$DEVICE_IP:8554/h264"

echo "========================================="
echo "IP_Cam RTSP Reliability Test"
echo "========================================="
echo "Target HTTP: $BASE_URL"
echo "Target RTSP: $RTSP_URL"
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Counters
SUCCESS_COUNT=0
FAIL_COUNT=0

# Function to test RTSP enable
test_rtsp_enable() {
    local attempt=$1
    echo -n "Attempt $attempt: Enabling RTSP... "
    
    RESPONSE=$(curl -s -f -m 10 "$BASE_URL/enableRTSP" 2>&1)
    CURL_EXIT=$?
    
    if [ $CURL_EXIT -eq 0 ]; then
        # Check if response contains success indicator
        if echo "$RESPONSE" | grep -q '"status":"ok"' || echo "$RESPONSE" | grep -q '"rtspEnabled":true'; then
            echo -e "${GREEN}✓ SUCCESS${NC}"
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
            return 0
        else
            echo -e "${RED}✗ FAILED (invalid response)${NC}"
            echo "Response: $RESPONSE" | head -c 200
            FAIL_COUNT=$((FAIL_COUNT + 1))
            return 1
        fi
    else
        echo -e "${RED}✗ FAILED (curl exit: $CURL_EXIT)${NC}"
        FAIL_COUNT=$((FAIL_COUNT + 1))
        return 1
    fi
}

# Function to test RTSP disable
test_rtsp_disable() {
    local attempt=$1
    echo -n "Attempt $attempt: Disabling RTSP... "
    
    RESPONSE=$(curl -s -f -m 10 "$BASE_URL/disableRTSP" 2>&1)
    CURL_EXIT=$?
    
    if [ $CURL_EXIT -eq 0 ]; then
        echo -e "${GREEN}✓ SUCCESS${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED (curl exit: $CURL_EXIT)${NC}"
        return 1
    fi
}

# Function to check RTSP status
check_rtsp_status() {
    STATUS=$(curl -s -m 5 "$BASE_URL/rtspStatus" 2>&1)
    if [ $? -eq 0 ]; then
        echo "$STATUS" | grep -o '"rtspEnabled":[^,}]*' | sed 's/"rtspEnabled":/RTSP Enabled: /'
        echo "$STATUS" | grep -o '"activeClients":[^,}]*' | sed 's/"activeClients":/Active Clients: /'
    else
        echo "Failed to get RTSP status"
    fi
}

# Test 1: Initial state check
echo "1. Initial RTSP state check:"
echo "----------------------------"
check_rtsp_status
echo ""

# Test 2: Single enable/disable cycle
echo "2. Single enable/disable cycle:"
echo "-------------------------------"
test_rtsp_enable 1
sleep 2
check_rtsp_status
sleep 1
test_rtsp_disable 1
sleep 1
echo ""

# Test 3: Rapid start/stop cycles (testing for EADDRINUSE)
echo "3. Rapid enable/disable cycles (10 iterations):"
echo "------------------------------------------------"
echo "This tests for bind failures (EADDRINUSE) on rapid restart"
echo ""

for i in {1..10}; do
    test_rtsp_enable $i
    sleep 0.5
    test_rtsp_disable $i
    sleep 0.5
done

echo ""
echo -e "${YELLOW}Rapid cycle test complete${NC}"
echo "Success: $SUCCESS_COUNT, Failures: $FAIL_COUNT"
echo ""

# Test 4: Multiple consecutive enables (testing retry logic)
echo "4. Multiple consecutive enables (5 iterations):"
echo "------------------------------------------------"
echo "This tests retry logic and proper state management"
echo ""

for i in {1..5}; do
    test_rtsp_enable $i
    sleep 1
done

echo ""
test_rtsp_disable "final"
sleep 1
echo ""

# Test 5: Enable and verify with VLC/ffprobe if available
echo "5. RTSP stream verification:"
echo "----------------------------"
test_rtsp_enable "final"
sleep 2

if command -v ffprobe &> /dev/null; then
    echo "Testing RTSP stream with ffprobe..."
    timeout 5 ffprobe -v error -show_entries stream=codec_type,codec_name -of default=noprint_wrappers=1 "$RTSP_URL" 2>&1 | head -10
else
    echo "ffprobe not available, skipping stream verification"
fi

echo ""

# Final status
echo "6. Final RTSP state:"
echo "--------------------"
check_rtsp_status
echo ""

# Summary
echo "========================================="
echo "Test Complete"
echo "========================================="
echo ""
echo "Summary:"
echo "  Total enable attempts: $((SUCCESS_COUNT + FAIL_COUNT))"
echo "  Successful: $SUCCESS_COUNT"
echo "  Failed: $FAIL_COUNT"
echo ""

if [ $FAIL_COUNT -eq 0 ]; then
    echo -e "${GREEN}✓ ALL TESTS PASSED${NC}"
    echo ""
    echo "The RTSP server successfully handled:"
    echo "  ✓ Rapid start/stop cycles without bind errors"
    echo "  ✓ Multiple consecutive enable requests"
    echo "  ✓ Proper state management"
    echo ""
    exit 0
else
    echo -e "${RED}✗ SOME TESTS FAILED${NC}"
    echo ""
    echo "Please check the device logs for details:"
    echo "  adb logcat -s RTSPServer:* CameraService:*"
    echo ""
    exit 1
fi
