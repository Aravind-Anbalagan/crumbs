import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import { GoogleOAuthProvider } from '@react-oauth/google';

// 🛑 IMPORTANT: Paste your actual Google Client ID right here!
const GOOGLE_CLIENT_ID = "46893975191-alcl611sm7k5928mmdt5od05ts0un15i.apps.googleusercontent.com";

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    {/* This provider is what prevents the Uncaught Error you saw */}
    <GoogleOAuthProvider clientId={GOOGLE_CLIENT_ID}>
      <App />
    </GoogleOAuthProvider>
  </React.StrictMode>,
);