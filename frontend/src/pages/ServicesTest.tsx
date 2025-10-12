import React, { useState } from 'react';
import { binomAPI, youtubeAPI, seleniumAPI } from '../services/api';

export const ServicesTest: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'binom' | 'youtube' | 'selenium'>('binom');
  
  // Binom state
  const [binomStatus, setBinomStatus] = useState<any>(null);
  const [binomLoading, setBinomLoading] = useState(false);
  
  // YouTube state
  const [youtubeUrl, setYoutubeUrl] = useState('https://www.youtube.com/watch?v=L0LxK3Ig_q8');
  const [youtubeStats, setYoutubeStats] = useState<any>(null);
  const [youtubeLoading, setYoutubeLoading] = useState(false);
  
  // Selenium state
  const [seleniumData, setSeleniumData] = useState({
    videoUrl: 'https://www.youtube.com/watch?v=L0LxK3Ig_q8',
    startTime: 0,
    endTime: 15,
  });
  const [seleniumJob, setSeleniumJob] = useState<any>(null);
  const [seleniumLoading, setSeleniumLoading] = useState(false);
  
  // Binom functions
  const testBinomConnection = async () => {
    setBinomLoading(true);
    try {
      const response = await binomAPI.testConnection();
      setBinomStatus(response);
    } catch (error: any) {
      console.error('Binom connection test failed:', error);
      setBinomStatus({ 
        error: error.response?.data?.message || error.message || 'Connection failed',
        details: error.response?.data 
      });
    } finally {
      setBinomLoading(false);
    }
  };
  
  const syncBinomCampaigns = async () => {
    setBinomLoading(true);
    try {
      const response = await binomAPI.syncCampaigns();
      setBinomStatus(response);
    } catch (error: any) {
      console.error('Binom sync failed:', error);
      setBinomStatus({ 
        error: error.response?.data?.message || error.message || 'Sync failed',
        details: error.response?.data 
      });
    } finally {
      setBinomLoading(false);
    }
  };
  
  // YouTube functions
  const checkYouTubeViews = async () => {
    if (!youtubeUrl) {
      alert('Please enter a YouTube URL');
      return;
    }
    
    setYoutubeLoading(true);
    try {
      const response = await youtubeAPI.checkVideoViews(youtubeUrl);
      setYoutubeStats(response);
    } catch (error: any) {
      console.error('YouTube check views failed:', error);
      setYoutubeStats({ 
        error: error.response?.data?.message || error.message || 'Failed to check views',
        details: error.response?.data 
      });
    } finally {
      setYoutubeLoading(false);
    }
  };
  
  // Selenium functions
  const createSeleniumClip = async () => {
    if (!seleniumData.videoUrl) {
      alert('Please enter a video URL');
      return;
    }
    
    setSeleniumLoading(true);
    try {
      const response = await seleniumAPI.createClip(seleniumData);
      setSeleniumJob(response);
      
      // Poll for status
      if (response.jobId) {
        const interval = setInterval(async () => {
          const status = await seleniumAPI.getClipStatus(response.jobId);
          setSeleniumJob(status);
          if (status.status === 'COMPLETED' || status.status === 'FAILED') {
            clearInterval(interval);
          }
        }, 2000);
      }
    } catch (error: any) {
      console.error('Selenium clip creation failed:', error);
      setSeleniumJob({ 
        error: error.response?.data?.message || error.message || 'Failed to create clip',
        details: error.response?.data 
      });
    } finally {
      setSeleniumLoading(false);
    }
  };
  
  return (
    <div className="px-4 py-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Test Services</h1>
      
      <div className="bg-white rounded-lg shadow">
        <div className="border-b border-gray-200">
          <nav className="-mb-px flex">
            <button
              onClick={() => setActiveTab('binom')}
              className={`py-2 px-6 block hover:text-blue-500 focus:outline-none ${
                activeTab === 'binom'
                  ? 'border-b-2 font-medium text-blue-600 border-blue-600'
                  : 'text-gray-600'
              }`}
            >
              Binom
            </button>
            <button
              onClick={() => setActiveTab('youtube')}
              className={`py-2 px-6 block hover:text-blue-500 focus:outline-none ${
                activeTab === 'youtube'
                  ? 'border-b-2 font-medium text-blue-600 border-blue-600'
                  : 'text-gray-600'
              }`}
            >
              YouTube
            </button>
            <button
              onClick={() => setActiveTab('selenium')}
              className={`py-2 px-6 block hover:text-blue-500 focus:outline-none ${
                activeTab === 'selenium'
                  ? 'border-b-2 font-medium text-blue-600 border-blue-600'
                  : 'text-gray-600'
              }`}
            >
              Selenium
            </button>
          </nav>
        </div>
        
        <div className="p-6">
          {activeTab === 'binom' && (
            <div>
              <h2 className="text-lg font-semibold mb-4">Binom Integration Test</h2>
              <p className="text-sm text-gray-600 mb-4">Test connection to Binom tracker and sync campaign data.</p>
              
              <div className="space-y-4">
                <div className="flex gap-4">
                  <button
                    onClick={testBinomConnection}
                    disabled={binomLoading}
                    className="bg-blue-500 hover:bg-blue-600 text-white px-4 py-2 rounded disabled:opacity-50"
                  >
                    {binomLoading ? 'Testing...' : 'Test Connection'}
                  </button>
                  
                  <button
                    onClick={syncBinomCampaigns}
                    disabled={binomLoading}
                    className="bg-green-500 hover:bg-green-600 text-white px-4 py-2 rounded disabled:opacity-50"
                  >
                    {binomLoading ? 'Syncing...' : 'Sync Campaigns'}
                  </button>
                </div>
                
                {binomStatus && (
                  <div className={`p-4 rounded ${binomStatus.error ? 'bg-red-50 border border-red-200' : 'bg-green-50 border border-green-200'}`}>
                    {binomStatus.error ? (
                      <div>
                        <p className="text-red-600 font-medium mb-2">Error: {binomStatus.error}</p>
                        {binomStatus.details && (
                          <pre className="text-xs text-gray-600 overflow-auto">{JSON.stringify(binomStatus.details, null, 2)}</pre>
                        )}
                      </div>
                    ) : (
                      <pre className="text-sm text-green-800">{JSON.stringify(binomStatus, null, 2)}</pre>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}
          
          {activeTab === 'youtube' && (
            <div>
              <h2 className="text-lg font-semibold mb-4">YouTube Video Check</h2>
              <p className="text-sm text-gray-600 mb-4">Check view count and statistics for any YouTube video.</p>
              
              <div className="space-y-4">
                <div>
                  <div className="text-xs text-gray-500 mb-2">
                    Example: https://www.youtube.com/watch?v=dQw4w9WgXcQ
                  </div>
                  <div className="flex gap-4">
                    <input
                      type="url"
                      value={youtubeUrl}
                      onChange={(e) => setYoutubeUrl(e.target.value)}
                      placeholder="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                      className="flex-1 px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  
                    <button
                      onClick={checkYouTubeViews}
                      disabled={youtubeLoading}
                      className="bg-blue-500 hover:bg-blue-600 text-white px-4 py-2 rounded disabled:opacity-50"
                    >
                      {youtubeLoading ? 'Checking...' : 'Check Views'}
                    </button>
                  </div>
                </div>
                
                {youtubeStats && (
                  <div className={`p-4 rounded ${youtubeStats.error ? 'bg-red-50 border border-red-200' : 'bg-green-50 border border-green-200'}`}>
                    {youtubeStats.error ? (
                      <div>
                        <p className="text-red-600 font-medium mb-2">Error: {youtubeStats.error}</p>
                        {youtubeStats.details && (
                          <pre className="text-xs text-gray-600 overflow-auto">{JSON.stringify(youtubeStats.details, null, 2)}</pre>
                        )}
                      </div>
                    ) : (
                      <div className="space-y-2">
                        <p><strong>Title:</strong> {youtubeStats.title}</p>
                        <p><strong>Views:</strong> {youtubeStats.viewCount?.toLocaleString()}</p>
                        <p><strong>Likes:</strong> {youtubeStats.likeCount?.toLocaleString()}</p>
                        <p><strong>Comments:</strong> {youtubeStats.commentCount?.toLocaleString()}</p>
                        <p><strong>Duration:</strong> {youtubeStats.duration}</p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}
          
          {activeTab === 'selenium' && (
            <div>
              <h2 className="text-lg font-semibold mb-4">Selenium Clip Creation</h2>
              <p className="text-sm text-gray-600 mb-4">Create a clip from a YouTube video using Selenium automation.</p>
              
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">
                    Video URL
                  </label>
                  <input
                    type="url"
                    value={seleniumData.videoUrl}
                    onChange={(e) => setSeleniumData(prev => ({ ...prev, videoUrl: e.target.value }))}
                    placeholder="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                    className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>
                
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      Start Time (seconds)
                    </label>
                    <input
                      type="number"
                      value={seleniumData.startTime}
                      onChange={(e) => setSeleniumData(prev => ({ ...prev, startTime: parseInt(e.target.value) || 0 }))}
                      min="0"
                      className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                  
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">
                      End Time (seconds)
                    </label>
                    <input
                      type="number"
                      value={seleniumData.endTime}
                      onChange={(e) => setSeleniumData(prev => ({ ...prev, endTime: parseInt(e.target.value) || 30 }))}
                      min="1"
                      className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  </div>
                </div>
                
                <button
                  onClick={createSeleniumClip}
                  disabled={seleniumLoading}
                  className="bg-purple-500 hover:bg-purple-600 text-white px-4 py-2 rounded disabled:opacity-50"
                >
                  {seleniumLoading ? 'Creating Clip...' : 'Create Clip'}
                </button>
                
                {seleniumJob && (
                  <div className={`p-4 rounded ${
                    seleniumJob.error ? 'bg-red-50 border border-red-200' : 
                    seleniumJob.status === 'COMPLETED' ? 'bg-green-50 border border-green-200' :
                    seleniumJob.status === 'FAILED' ? 'bg-red-50 border border-red-200' :
                    'bg-yellow-50 border border-yellow-200'
                  }`}>
                    {seleniumJob.error ? (
                      <div>
                        <p className="text-red-600 font-medium mb-2">Error: {seleniumJob.error}</p>
                        {seleniumJob.details && (
                          <pre className="text-xs text-gray-600 overflow-auto">{JSON.stringify(seleniumJob.details, null, 2)}</pre>
                        )}
                      </div>
                    ) : (
                      <div className="space-y-2">
                        <p><strong>Job ID:</strong> {seleniumJob.jobId}</p>
                        <p><strong>Status:</strong> {seleniumJob.status}</p>
                        <p><strong>Progress:</strong> {seleniumJob.progress}%</p>
                        {seleniumJob.clipUrl && (
                          <p><strong>Clip URL:</strong> <a href={seleniumJob.clipUrl} target="_blank" className="text-blue-600 hover:underline">{seleniumJob.clipUrl}</a></p>
                        )}
                        {seleniumJob.error && (
                          <p className="text-red-600"><strong>Error:</strong> {seleniumJob.error}</p>
                        )}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};