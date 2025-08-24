#!/bin/bash

# Test script for enhanced YouTube API v3 implementation
# This script demonstrates all the new YouTube API methods

BASE_URL="http://localhost:8080/api/v1/youtube"
ADMIN_TOKEN="your-admin-token-here"

echo "========================================="
echo "YouTube API v3 - Enhanced Implementation"
echo "========================================="

# Test 1: Get video details with all parts
echo -e "\n1. Get comprehensive video details (videos.list)"
echo "   Quota cost: 1 unit"
curl -X GET "$BASE_URL/videos?ids=dQw4w9WgXcQ&parts=snippet,statistics,status,contentDetails" \
  -H "Content-Type: application/json" | jq '.'

# Test 2: Search for videos
echo -e "\n2. Search YouTube (search.list)"
echo "   Quota cost: 100 units"
curl -X GET "$BASE_URL/search?q=programming&type=video&maxResults=5" \
  -H "Content-Type: application/json" | jq '.'

# Test 3: Get channel information
echo -e "\n3. Get channel info (channels.list)"
echo "   Quota cost: 1 unit"
curl -X GET "$BASE_URL/channels/UC8butISFwT-Wl7EV0hUK0BQ" \
  -H "Content-Type: application/json" | jq '.'

# Test 4: Get playlist items
echo -e "\n4. Get playlist items (playlistItems.list)"
echo "   Quota cost: 1 unit"
# Using a public playlist ID
curl -X GET "$BASE_URL/playlists/PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf/items?maxResults=10" \
  -H "Content-Type: application/json" | jq '.'

# Test 5: Get video comments
echo -e "\n5. Get video comments (commentThreads.list)"
echo "   Quota cost: 1 unit"
curl -X GET "$BASE_URL/videos/dQw4w9WgXcQ/comments?maxResults=5" \
  -H "Content-Type: application/json" | jq '.'

# Test 6: Get channel uploads
echo -e "\n6. Get channel uploads"
echo "   Uses channels.list + playlistItems.list"
curl -X GET "$BASE_URL/channels/UC8butISFwT-Wl7EV0hUK0BQ/uploads?maxVideos=5" \
  -H "Content-Type: application/json" | jq '.'

# Test 7: Batch get video statistics (optimized)
echo -e "\n7. Batch get video statistics"
echo "   Optimized for multiple videos - reduces quota usage"
curl -X POST "$BASE_URL/videos/batch-statistics" \
  -H "Content-Type: application/json" \
  -d '["dQw4w9WgXcQ", "jNQXAC9IVRw", "kJQP7kiw5Fk"]' | jq '.'

# Test 8: Extract video ID from URL
echo -e "\n8. Extract video ID from URL"
curl -X GET "$BASE_URL/extract-video-id?url=https://www.youtube.com/watch?v=dQw4w9WgXcQ" \
  -H "Content-Type: application/json" | jq '.'

# Test 9: Verify video exists
echo -e "\n9. Verify video exists and is public"
curl -X GET "$BASE_URL/verify-video/dQw4w9WgXcQ" \
  -H "Content-Type: application/json" | jq '.'

# Test 10: Check quota status (requires auth)
echo -e "\n10. Check quota status"
curl -X GET "$BASE_URL/quota/status" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" | jq '.'

echo -e "\n========================================="
echo "Testing complete!"
echo "========================================="

# Summary of quota costs:
echo -e "\nQuota Usage Summary:"
echo "- videos.list: 1 unit per call"
echo "- search.list: 100 units per call"
echo "- channels.list: 1 unit per call"
echo "- playlistItems.list: 1 unit per call"
echo "- commentThreads.list: 1 unit per call"
echo ""
echo "Daily quota limit: 10,000 units"
echo "Caching enabled to reduce quota usage:"
echo "- Video stats: 5 minutes"
echo "- General info: 10 minutes"
echo "- Channel info: 1 hour"