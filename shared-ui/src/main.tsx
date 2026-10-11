import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './index.css';

const root = document.getElementById('root');
if (!root) throw new Error('NeoTUN UI root element was not found');
createRoot(root).render(<React.StrictMode><App /></React.StrictMode>);
