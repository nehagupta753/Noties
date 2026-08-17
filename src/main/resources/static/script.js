// ========================================
//  Noties 📝 — Frontend Logic
// ========================================

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

// DOM Elements
const urlInput = $('#youtube-url');
const pasteBtn = $('#paste-btn');
const generateBtn = $('#generate-btn');
const videoPreview = $('#video-preview');
const videoThumbnail = $('#video-thumbnail');
const inputSection = $('#input-section');
const loadingSection = $('#loading-section');
const errorSection = $('#error-section');
const notesSection = $('#notes-section');
const errorMessage = $('#error-message');
const tryAgainBtn = $('#try-again-btn');
const copyBtn = $('#copy-btn');
const downloadBtn = $('#download-btn');
const newBtn = $('#new-btn');
const editBtn = $('#edit-btn');
const saveEditsBtn = $('#save-edits-btn');
const notesContent = $('#notes-content');
const transcriptCount = $('#transcript-count');
const notesTimestampEl = $('#notes-timestamp');
const timestampGenerated = $('#timestamp-generated');
const timestampEdited = $('#timestamp-edited');

// Tab Elements
const tabDetailed = $('#tab-detailed');
const tabRevision = $('#tab-revision');

// History Elements
const historySidebar = $('#history-sidebar');
const historyOverlay = $('#history-overlay');
const historyToggleBtn = $('#history-toggle-btn');
const closeSidebarBtn = $('#close-sidebar-btn');
const clearHistoryBtn = $('#clear-history-btn');
const historyList = $('#history-list');

// Panda Mascot Elements
const pandaMascot = $('#panda-mascot');
const pandaBubble = $('#panda-bubble');
const pandaBodyWrapper = $('.panda-body-wrapper');

// State
let rawNotesMarkdown = '';
let rawRevisionMarkdown = '';
let activeTab = 'detailed'; // 'detailed' | 'revision'
let currentVideoId = '';
let currentVideoTitle = '';
let isEditMode = false;
let generatedAt = null;   // ISO string — when notes were generated
let lastEditedAt = null;  // ISO string — when notes were last edited

// ----------------------------------------
//  Timestamp Helpers
// ----------------------------------------

function formatTimestamp(isoStr) {
  if (!isoStr) return '';
  const d = new Date(isoStr);
  return d.toLocaleDateString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric'
  }) + ', ' + d.toLocaleTimeString(undefined, {
    hour: 'numeric', minute: '2-digit', hour12: true
  });
}

function renderTimestamps() {
  if (!generatedAt) {
    notesTimestampEl.style.display = 'none';
    return;
  }
  notesTimestampEl.style.display = 'flex';
  timestampGenerated.innerHTML = `📅 Generated: ${formatTimestamp(generatedAt)}`;
  if (lastEditedAt) {
    timestampEdited.innerHTML = `✏️ Last edited: ${formatTimestamp(lastEditedAt)}`;
    timestampEdited.style.display = 'inline-flex';
  } else {
    timestampEdited.style.display = 'none';
  }
}

// ----------------------------------------
//  Edit Mode
// ----------------------------------------

function enableEditMode() {
  isEditMode = true;
  notesContent.setAttribute('contenteditable', 'true');
  notesContent.classList.add('editable');
  editBtn.classList.add('save-edits-btn');
  editBtn.innerHTML = `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
      <path d="M19 21H5C3.89543 21 3 20.1046 3 19V5C3 3.89543 3.89543 3 5 3H16L21 8V19C21 20.1046 20.1046 21 19 21Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M17 21V13H7V21" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M7 3V8H15" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
    <span>Save</span>
  `;
  if (saveEditsBtn) saveEditsBtn.style.display = 'none';
  triggerPandaBubble('Edit mode ON! ✏️ Make your changes 🐼');
}

function disableEditMode() {
  isEditMode = false;
  notesContent.setAttribute('contenteditable', 'false');
  notesContent.classList.remove('editable');
  editBtn.classList.remove('save-edits-btn', 'edit-active');
  editBtn.innerHTML = `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
      <path d="M11 4H4C3.46957 4 2.96086 4.21071 2.58579 4.58579C2.21071 4.96086 2 5.46957 2 6V20C2 20.5304 2.21071 21.0391 2.58579 21.4142C2.96086 21.7893 3.46957 22 4 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
      <path d="M18.5 2.50001C18.8978 2.10219 19.4374 1.87869 20 1.87869C20.5626 1.87869 21.1022 2.10219 21.5 2.50001C21.8978 2.89784 22.1213 3.4374 22.1213 4.00001C22.1213 4.56262 21.8978 5.10219 21.5 5.50001L12 15L8 16L9 12L18.5 2.50001Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
    <span>Edit</span>
  `;
  if (saveEditsBtn) saveEditsBtn.style.display = 'none';
}

function saveEdits() {
  const editedHtml = notesContent.innerHTML;
  
  if (activeTab === 'detailed') {
    rawNotesMarkdown = '<!--HTML_EDITED-->' + editedHtml;
  } else {
    rawRevisionMarkdown = '<!--HTML_EDITED-->' + editedHtml;
  }
  
  lastEditedAt = new Date().toISOString();
  renderTimestamps();
  
  // Update history in localStorage
  if (currentVideoId) {
    const dateStr = new Date().toLocaleDateString(undefined, {
      month: 'short', day: 'numeric', year: 'numeric',
    });
    saveHistoryItem({
      videoId: currentVideoId,
      title: currentVideoTitle,
      notes: rawNotesMarkdown,
      revision: rawRevisionMarkdown,
      transcriptLength: transcriptCount.querySelector('span').textContent.replace(' transcript segments', '') || '—',
      date: dateStr,
      generatedAt,
      lastEditedAt,
    });
  }
  
  disableEditMode();
  showToast('Changes saved! ✨');
  triggerPandaBubble('Edits saved successfully! 💾🌸');
}

// ----------------------------------------
//  YouTube URL Handling
// ----------------------------------------

function extractVideoId(url) {
  const patterns = [
    /(?:youtube\.com\/watch\?v=)([a-zA-Z0-9_-]{11})/,
    /(?:youtube\.com\/embed\/)([a-zA-Z0-9_-]{11})/,
    /(?:youtube\.com\/v\/)([a-zA-Z0-9_-]{11})/,
    /(?:youtu\.be\/)([a-zA-Z0-9_-]{11})/,
    /(?:youtube\.com\/shorts\/)([a-zA-Z0-9_-]{11})/,
    /^([a-zA-Z0-9_-]{11})$/,
  ];

  for (const pattern of patterns) {
    const match = url.trim().match(pattern);
    if (match) return match[1];
  }
  return null;
}



function showVideoPreview(videoId) {
  const thumbUrl = `https://img.youtube.com/vi/${videoId}/maxresdefault.jpg`;
  const fallbackUrl = `https://img.youtube.com/vi/${videoId}/hqdefault.jpg`;

  videoThumbnail.onerror = () => {
    videoThumbnail.onerror = null;
    videoThumbnail.src = fallbackUrl;
  };
  videoThumbnail.src = thumbUrl;
  videoPreview.style.display = 'block';
}

function hideVideoPreview() {
  videoPreview.style.display = 'none';
  videoThumbnail.src = '';
}

// Auto-detect URL on input change
urlInput.addEventListener('input', () => {
  const url = urlInput.value;
  const videoId = extractVideoId(url);
  
  if (videoId) {
    showVideoPreview(videoId);
  } else {
    hideVideoPreview();
  }
});

// Paste from clipboard
pasteBtn.addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText();
    urlInput.value = text;
    urlInput.dispatchEvent(new Event('input'));
  } catch {
    urlInput.focus();
  }
});

// ----------------------------------------
//  Section Visibility
// ----------------------------------------

function showSection(section) {
  [inputSection, loadingSection, errorSection, notesSection].forEach((s) => {
    s.style.display = 'none';
  });
  section.style.display = 'block';
}

function showInputAndNotes() {
  inputSection.style.display = 'block';
  notesSection.style.display = 'block';
  loadingSection.style.display = 'none';
  errorSection.style.display = 'none';
}

// ----------------------------------------
//  Loading Animation
// ----------------------------------------

let loadingInterval = null;

function startLoadingAnimation() {
  const steps = [
    { el: $('#step-1'), delay: 0 },
    { el: $('#step-2'), delay: 3000 },
    { el: $('#step-3'), delay: 8000 },
  ];

  steps.forEach(({ el }) => {
    el.classList.remove('active', 'done');
  });

  steps[0].el.classList.add('active');

  const timers = [];

  // Step 2: Analyzing
  timers.push(
    setTimeout(() => {
      steps[0].el.classList.remove('active');
      steps[0].el.classList.add('done');
      steps[1].el.classList.add('active');
    }, steps[1].delay)
  );

  // Step 3: Crafting
  timers.push(
    setTimeout(() => {
      steps[1].el.classList.remove('active');
      steps[1].el.classList.add('done');
      steps[2].el.classList.add('active');
    }, steps[2].delay)
  );

  loadingInterval = timers;
}

function stopLoadingAnimation() {
  if (loadingInterval) {
    loadingInterval.forEach(clearTimeout);
    loadingInterval = null;
  }
}

// ----------------------------------------
//  Markdown to HTML Converter
// ----------------------------------------

function markdownToHtml(md) {
  let html = md;

  html = html
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  // Code blocks
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    return `<pre><code class="language-${lang || 'text'}">${code.trim()}</code></pre>`;
  });

  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  // Headings
  html = html.replace(/^#### (.+)$/gm, '<h4>$1</h4>');
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

  // Horizontal rules
  html = html.replace(/^---$/gm, '<hr>');

  // Bold + Italic
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // Blockquotes
  html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');
  html = html.replace(/<\/blockquote>\n<blockquote>/g, '\n');

  // Tables
  html = html.replace(
    /(?:^\|.+\|$\n?)+/gm,
    (tableBlock) => {
      const rows = tableBlock.trim().split('\n');
      if (rows.length < 2) return tableBlock;

      let table = '<table>';

      const headerCells = rows[0]
        .split('|')
        .filter((c) => c.trim() !== '')
        .map((c) => `<th>${c.trim()}</th>`)
        .join('');
      table += `<thead><tr>${headerCells}</tr></thead>`;

      const startIndex = /^\|[\s-:|]+\|$/.test(rows[1]) ? 2 : 1;

      table += '<tbody>';
      for (let i = startIndex; i < rows.length; i++) {
        const cells = rows[i]
          .split('|')
          .filter((c) => c.trim() !== '')
          .map((c) => `<td>${c.trim()}</td>`)
          .join('');
        if (cells) table += `<tr>${cells}</tr>`;
      }
      table += '</tbody></table>';

      return table;
    }
  );

  // Unordered lists
  html = html.replace(/^(\s*)[-*] (.+)$/gm, (_, indent, content) => {
    const level = Math.floor(indent.length / 2);
    return `<li style="margin-left: ${level * 20}px">${content}</li>`;
  });
  html = html.replace(/((?:<li[^>]*>.*<\/li>\n?)+)/g, '<ul>$1</ul>');

  // Ordered lists
  html = html.replace(/^\d+\. (.+)$/gm, '<li>$1</li>');

  // Paragraphs
  html = html.replace(
    /^(?!<[a-z/])((?!<).+)$/gm,
    '<p>$1</p>'
  );

  html = html.replace(/\n{3,}/g, '\n\n');

  return html;
}

// ----------------------------------------
//  Toast Notifications
// ----------------------------------------

function showToast(message, type = 'success') {
  const existingToast = $('.toast');
  if (existingToast) existingToast.remove();

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.innerHTML = `
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
      <path d="M20 6L9 17L4 12" stroke="${type === 'success' ? '#a7c957' : '#ff5c8a'}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
    </svg>
    ${message}
  `;
  document.body.appendChild(toast);

  requestAnimationFrame(() => {
    toast.classList.add('show');
  });

  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 400);
  }, 3000);
}

// ----------------------------------------
//  Tabs Manager
// ----------------------------------------

function renderActiveTab() {
  // Exit edit mode when switching tabs
  if (isEditMode) disableEditMode();
  
  if (activeTab === 'detailed') {
    // Check if this is pre-edited HTML content
    if (rawNotesMarkdown.startsWith('<!--HTML_EDITED-->')) {
      notesContent.innerHTML = rawNotesMarkdown.replace('<!--HTML_EDITED-->', '');
    } else {
      notesContent.innerHTML = markdownToHtml(rawNotesMarkdown);
    }
    tabDetailed.classList.add('active');
    tabRevision.classList.remove('active');
  } else {
    if (rawRevisionMarkdown.startsWith('<!--HTML_EDITED-->')) {
      notesContent.innerHTML = rawRevisionMarkdown.replace('<!--HTML_EDITED-->', '');
    } else {
      notesContent.innerHTML = markdownToHtml(rawRevisionMarkdown);
    }
    tabRevision.classList.add('active');
    tabDetailed.classList.remove('active');
  }
  renderTimestamps();
}

tabDetailed.addEventListener('click', () => {
  if (activeTab !== 'detailed') {
    activeTab = 'detailed';
    renderActiveTab();
    triggerPandaBubble('Switched to Detailed Notes! 📚🌸');
  }
});

tabRevision.addEventListener('click', () => {
  if (activeTab !== 'revision') {
    activeTab = 'revision';
    renderActiveTab();
    triggerPandaBubble('Here are your quick revision points! ⚡🧸');
  }
});

// ----------------------------------------
//  History feature (localStorage)
// ----------------------------------------

function getSavedHistory() {
  const history = localStorage.getItem('notes_history');
  return history ? JSON.parse(history) : [];
}

function saveHistoryItem(item) {
  const history = getSavedHistory();
  const filtered = history.filter((i) => i.videoId !== item.videoId);
  filtered.unshift(item);
  localStorage.setItem('notes_history', JSON.stringify(filtered));
  renderHistoryList();
}

function deleteHistoryItem(videoId, event) {
  if (event) event.stopPropagation();
  const history = getSavedHistory();
  const filtered = history.filter((i) => i.videoId !== videoId);
  localStorage.setItem('notes_history', JSON.stringify(filtered));
  renderHistoryList();
  showToast('Note deleted from history');
}

function clearAllHistory() {
  if (confirm('Are you sure you want to clear your saved notes history? 🧸')) {
    localStorage.removeItem('notes_history');
    renderHistoryList();
    showToast('History cleared!');
  }
}

function renderHistoryList() {
  const history = getSavedHistory();
  historyList.innerHTML = '';

  if (history.length === 0) {
    historyList.innerHTML = `
      <div class="history-empty">
        <p>No saved notes yet! 🌸</p>
        <p class="history-empty-subtitle">Generate some notes to see them here.</p>
      </div>
    `;
    return;
  }

  history.forEach((item) => {
    const div = document.createElement('div');
    div.className = 'history-item';
    const displayTitle = item.title || 'YouTube Video';
    const safeTitle = displayTitle.replace(/"/g, '&quot;').replace(/</g, '&lt;');
    div.innerHTML = `
      <img src="https://img.youtube.com/vi/${item.videoId}/hqdefault.jpg" class="history-item-thumb" alt="${safeTitle}" onerror="this.style.display='none'">
      <div class="history-item-details">
        <div class="history-item-row">
          <div class="history-item-play-btn">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" fill="#fff0f3"/>
              <path d="M10 8.5L15 12L10 15.5V8.5Z" fill="#ff5c8a"/>
            </svg>
          </div>
          <div class="history-item-title" title="${safeTitle}">${safeTitle}</div>
        </div>
      </div>
      <button class="history-item-delete" title="Delete note">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="3 6 5 6 21 6"></polyline>
          <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
        </svg>
      </button>
    `;

    div.addEventListener('click', () => {
      loadHistoryNotes(item);
      toggleHistorySidebar(false);
    });

    div.querySelector('.history-item-delete').addEventListener('click', (e) => {
      deleteHistoryItem(item.videoId, e);
    });

    historyList.appendChild(div);
  });
}

function loadHistoryNotes(item) {
  rawNotesMarkdown = item.notes;
  rawRevisionMarkdown = item.revision || '# Quick Revision Notes\n\n*(No revision notes saved for this older entry)*';
  currentVideoId = item.videoId;
  currentVideoTitle = item.title;
  generatedAt = item.generatedAt || null;
  lastEditedAt = item.lastEditedAt || null;
  
  if (isEditMode) disableEditMode();
  activeTab = 'detailed';
  renderActiveTab();
  
  transcriptCount.querySelector('span').textContent = `${item.transcriptLength || '—'} transcript segments`;
  
  urlInput.value = `https://www.youtube.com/watch?v=${item.videoId}`;
  showVideoPreview(item.videoId);
  showInputAndNotes();

  notesSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
  showToast('Notes loaded from history! 🧸');
}

function toggleHistorySidebar(show) {
  if (show) {
    historySidebar.classList.add('open');
    historyOverlay.classList.add('open');
    triggerPandaBubble('Check out your saved notes! 📚');
  } else {
    historySidebar.classList.remove('open');
    historyOverlay.classList.remove('open');
  }
}

historyToggleBtn.addEventListener('click', () => toggleHistorySidebar(true));
closeSidebarBtn.addEventListener('click', () => toggleHistorySidebar(false));
historyOverlay.addEventListener('click', () => toggleHistorySidebar(false));
clearHistoryBtn.addEventListener('click', clearAllHistory);

// ----------------------------------------
//  Generate Notes
// ----------------------------------------

async function generateNotes() {
  const url = urlInput.value.trim();

  if (!url) {
    urlInput.focus();
    urlInput.style.outline = '3px solid var(--accent-pink-medium)';
    setTimeout(() => (urlInput.style.outline = ''), 2000);
    return;
  }

  const videoId = extractVideoId(url);
  
  if (!videoId) {
    showToast('Please enter a valid YouTube video URL 🌸', 'error');
    return;
  }

  currentVideoId = videoId;

  showSection(loadingSection);
  generateBtn.disabled = true;
  startLoadingAnimation();
  
  const progressContainer = document.getElementById('progress-bar-container');
  const progressFill = document.getElementById('progress-bar-fill');
  const progressText = document.getElementById('progress-text');
  const loadingTitle = document.getElementById('loading-title');
  const loadingHint = document.getElementById('loading-hint');

  if (loadingTitle) loadingTitle.textContent = 'Generating Your Notes';
  triggerPandaBubble('Writing notes and revision sheets... 📝');

  const endpoint = '/api/generate-notes';

  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });

    if (!response.ok && response.headers.get('content-type')?.includes('application/json')) {
      const errData = await response.json();
      throw new Error(errData.error || 'Failed to generate notes');
    }

    // Read SSE stream
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (value) {
        buffer += decoder.decode(value, { stream: true });
      }

      // Standard SSE messages are separated by \n\n
      const messages = buffer.split('\n\n');
      buffer = done ? '' : (messages.pop() || '');

      for (const msg of messages) {
        const lines = msg.split('\n');
        let dataPayload = '';
        for (const line of lines) {
          if (line.startsWith('data:')) {
            dataPayload += (dataPayload ? '\n' : '') + line.replace(/^data:\s*/, '');
          }
        }
        if (!dataPayload) continue;

        let event;
        try {
          event = JSON.parse(dataPayload);
        } catch (e) {
          continue;
        }

        if (event.type === 'progress' || event.type === 'video-progress') {
          if (progressContainer) progressContainer.style.display = 'block';
          if (progressFill) progressFill.style.width = `${event.progress || 0}%`;
          if (progressText) progressText.textContent = event.message || 'Processing...';
          
          if (event.progress > 10) {
            const step1 = document.getElementById('step-1');
            if (step1) { step1.classList.remove('active'); step1.classList.add('done'); }
            const step2 = document.getElementById('step-2');
            if (step2) step2.classList.add('active');
          }
          if (event.progress > 70) {
            const step2 = document.getElementById('step-2');
            if (step2) { step2.classList.remove('active'); step2.classList.add('done'); }
            const step3 = document.getElementById('step-3');
            if (step3) step3.classList.add('active');
          }
        }



        if (event.type === 'error') {
          throw new Error(event.error);
        }

        if (event.type === 'complete') {
          rawNotesMarkdown = event.notes;
          rawRevisionMarkdown = event.revision;
          currentVideoTitle = event.videoTitle || 'YouTube Video';
          currentVideoId = event.videoId || currentVideoId;
          generatedAt = new Date().toISOString();
          lastEditedAt = null;

          if (isEditMode) disableEditMode();
          activeTab = 'detailed';
          renderActiveTab();

          const segmentLabel = `${event.transcriptLength} transcript segments`;
          transcriptCount.querySelector('span').textContent = segmentLabel;

          const dateStr = new Date().toLocaleDateString(undefined, {
            month: 'short',
            day: 'numeric',
            year: 'numeric',
          });

          saveHistoryItem({
            videoId: currentVideoId,
            title: currentVideoTitle,
            notes: rawNotesMarkdown,
            revision: rawRevisionMarkdown,
            transcriptLength: event.transcriptLength,
            date: dateStr,
            generatedAt,
            lastEditedAt,
          });

          showInputAndNotes();
          notesSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
          triggerPandaBubble('Notes ready! 🎉🌸');
        }
      }
      if (done) break;
    }
  } catch (error) {
    showSection(errorSection);
    inputSection.style.display = 'block';
    errorMessage.textContent = error.message;
    triggerPandaBubble('Oh no! Something went wrong... 😿');
  } finally {
    generateBtn.disabled = false;
    stopLoadingAnimation();
    if (progressContainer) progressContainer.style.display = 'none';
    if (progressFill) progressFill.style.width = '0%';
  }
}

// ----------------------------------------
//  Action Buttons
// ----------------------------------------

generateBtn.addEventListener('click', generateNotes);

urlInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') generateNotes();
});

tryAgainBtn.addEventListener('click', () => {
  showSection(inputSection);
  urlInput.focus();
});

// Edit / Save toggle button
editBtn.addEventListener('click', () => {
  if (isEditMode) {
    saveEdits();
  } else {
    enableEditMode();
  }
});

copyBtn.addEventListener('click', async () => {
  // If in edit mode, copy the current visible text
  let textToCopy;
  if (isEditMode) {
    textToCopy = notesContent.innerText;
  } else {
    const raw = activeTab === 'detailed' ? rawNotesMarkdown : rawRevisionMarkdown;
    textToCopy = raw.startsWith('<!--HTML_EDITED-->') ? notesContent.innerText : raw;
  }
  try {
    await navigator.clipboard.writeText(textToCopy);
    copyBtn.classList.add('copied');
    copyBtn.querySelector('span').textContent = 'Copied!';
    showToast(`${activeTab === 'detailed' ? 'Notes' : 'Revision sheet'} copied! 🌸`);
    triggerPandaBubble('Copied to clipboard! 📋💖');
    setTimeout(() => {
      copyBtn.classList.remove('copied');
      copyBtn.querySelector('span').textContent = 'Copy';
    }, 2000);
  } catch {
    showToast('Failed to copy', 'error');
  }
});

downloadBtn.addEventListener('click', () => {
  const title = activeTab === 'detailed' ? '📚 Detailed Study Notes' : '⚡ Quick Revision Notes';
  const subtitle = currentVideoTitle || 'YouTube Video';

  // Build timestamp line for PDF header
  let timestampLine = '';
  if (generatedAt) {
    timestampLine += `Generated: ${formatTimestamp(generatedAt)}`;
  }
  if (lastEditedAt) {
    timestampLine += `  •  Last edited: ${formatTimestamp(lastEditedAt)}`;
  }

  // Create a temporary container for PDF rendering
  const pdfContainer = document.createElement('div');
  pdfContainer.style.fontFamily = "'Segoe UI', 'Inter', sans-serif";
  pdfContainer.style.color = '#2d2d2d';
  pdfContainer.style.lineHeight = '1.75';
  pdfContainer.style.fontSize = '14px';
  pdfContainer.style.padding = '20px';
  pdfContainer.style.background = '#ffffff';
  pdfContainer.style.width = '794px'; // standard A4 pixel width at 96 DPI
  pdfContainer.style.position = 'absolute';
  pdfContainer.style.left = '-9999px';
  pdfContainer.style.top = '0';

  // Header
  pdfContainer.innerHTML = `
    <div style="text-align: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 2px solid #ffccd5;">
      <h1 style="font-size: 22px; color: #e0457b; margin: 0 0 4px; border: none; padding: 0;">${title}</h1>
      <p style="color: #8c6a71; font-size: 13px; margin: 0 0 4px;">${subtitle}</p>
      ${timestampLine ? `<p style="color: #a88a91; font-size: 11px; margin: 0;">🕐 ${timestampLine}</p>` : ''}
    </div>
    ${notesContent.innerHTML}
  `;

  // Clean filename
  const safeTitle = subtitle.replace(/[^a-zA-Z0-9 ]/g, '').trim().replace(/\s+/g, '_').substring(0, 50);
  const dateStr = new Date().toISOString().split('T')[0];
  const filename = `Noties_${safeTitle || 'Notes'}_${dateStr}.pdf`;

  // Disable edit mode visually in the clone
  const editableDivs = pdfContainer.querySelectorAll('[contenteditable]');
  editableDivs.forEach(el => el.removeAttribute('contenteditable'));

  document.body.appendChild(pdfContainer);

  // Check if html2pdf is loaded
  if (typeof html2pdf !== 'undefined') {
    const opt = {
      margin: [10, 10, 10, 10],
      filename: filename,
      image: { type: 'jpeg', quality: 0.98 },
      html2canvas: { scale: 2, useCORS: true, letterRendering: true, logging: false },
      jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' },
      pagebreak: { mode: ['avoid-all', 'css', 'legacy'] }
    };

    showToast('Generating PDF... 📄');
    triggerPandaBubble('Downloading your notes as PDF! 📄💖');

    html2pdf().set(opt).from(pdfContainer).save().then(() => {
      document.body.removeChild(pdfContainer);
      showToast('PDF downloaded! 🎉');
    }).catch((err) => {
      console.error('PDF generation error:', err);
      if (pdfContainer.parentNode) document.body.removeChild(pdfContainer);
      showToast('Opening print dialog...', 'info');
      fallbackPrintPDF(title, subtitle, timestampLine);
    });
  } else {
    document.body.removeChild(pdfContainer);
    fallbackPrintPDF(title, subtitle, timestampLine);
  }
});

// Fallback PDF via print dialog (in case html2pdf fails)
function fallbackPrintPDF(title, subtitle, timestampLine) {
  const renderedHtml = notesContent.innerHTML;
  const printWindow = window.open('', '_blank');
  printWindow.document.write(`<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <title>${subtitle} — ${title}</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Segoe UI', 'Inter', sans-serif;
      color: #2d2d2d;
      line-height: 1.75;
      padding: 40px 48px;
      font-size: 14px;
      max-width: 800px;
      margin: 0 auto;
    }
    .pdf-header {
      text-align: center;
      margin-bottom: 28px;
      padding-bottom: 18px;
      border-bottom: 2px solid #ffccd5;
    }
    .pdf-header h1 { font-size: 24px; color: #e0457b; margin-bottom: 4px; }
    .pdf-header p { color: #8c6a71; font-size: 13px; }
    .pdf-header .timestamp { color: #a88a91; font-size: 11px; }
    h1 { font-size: 22px; color: #e0457b; margin: 24px 0 10px; border-bottom: 2px solid #ffccd5; padding-bottom: 6px; }
    h2 { font-size: 18px; color: #e0457b; margin: 20px 0 8px; }
    h3 { font-size: 15px; color: #ff5c8a; margin: 16px 0 6px; }
    h4 { font-size: 14px; color: #ff85a1; margin: 12px 0 4px; }
    p { margin: 6px 0; }
    strong { color: #4a373b; }
    pre {
      background: #f8f8f8;
      border: 1px solid #e0e0e0;
      border-radius: 6px;
      padding: 12px 14px;
      margin: 10px 0;
      font-size: 12px;
      white-space: pre-wrap;
      word-wrap: break-word;
    }
    code { font-family: 'Courier New', monospace; font-size: 12px; }
    :not(pre) > code {
      background: #fff0f3;
      color: #d63384;
      padding: 2px 5px;
      border-radius: 4px;
    }
    table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 13px; }
    th { background: #fff0f3; color: #e0457b; padding: 8px 10px; border: 1px solid #ffccd5; text-align: left; font-weight: 700; }
    td { padding: 7px 10px; border: 1px solid #ffe5ec; }
    blockquote { border-left: 3px solid #ff85a1; padding: 8px 14px; margin: 10px 0; background: #fff8fa; color: #6b4f54; }
    hr { border: none; border-top: 2px solid #ffe5ec; margin: 18px 0; }
    ul, ol { padding-left: 22px; margin: 6px 0; }
    li { margin: 3px 0; }
    @media print {
      body { padding: 20px 24px; }
      @page { margin: 15mm 12mm; }
    }
  </style>
</head>
<body>
  <div class="pdf-header">
    <h1 style="border: none; margin: 0 0 4px; padding: 0;">${title}</h1>
    <p>${subtitle}</p>
    ${timestampLine ? `<p class="timestamp">🕐 ${timestampLine}</p>` : ''}
  </div>
  ${renderedHtml}
  <script>
    window.onafterprint = () => window.close();
    window.onload = () => setTimeout(() => window.print(), 300);
  <\/script>
</body>
</html>`);
  printWindow.document.close();
}

newBtn.addEventListener('click', () => {
  if (isEditMode) disableEditMode();
  urlInput.value = '';
  hideVideoPreview();
  rawNotesMarkdown = '';
  rawRevisionMarkdown = '';
  currentVideoId = '';
  currentVideoTitle = '';
  generatedAt = null;
  lastEditedAt = null;
  notesContent.innerHTML = '';
  notesTimestampEl.style.display = 'none';
  showSection(inputSection);
  urlInput.focus();
  triggerPandaBubble('Ready for a new video! 🎬🌸');
});

// ----------------------------------------
//  Panda Mascot Mouse Follower
// ----------------------------------------

const pandaPos = { x: 100, y: 100 };
const mousePos = { x: 150, y: 150 };
const pandaSpeed = 0.08;
let pandaFacingLeft = false;
let isAvoiding = false;

const pandaOffsetX = 20; 
const pandaOffsetY = 20;

window.addEventListener('mousemove', (e) => {
  mousePos.x = e.clientX;
  mousePos.y = e.clientY;
  
  // Detect if mouse is hovering over an interactive element
  const interactiveEl = e.target.closest('button, input, a, select, textarea, [role="button"], .history-item, .panda-chat-window, .history-sidebar');
  isAvoiding = !!interactiveEl;
});

function updatePandaPosition() {
  let targetX, targetY;
  
  if (isAvoiding) {
    // Rest in the bottom-left corner to avoid overlapping interactive buttons
    targetX = 30;
    targetY = window.innerHeight - 145;
    pandaMascot.classList.add('avoiding');
    pandaBodyWrapper.style.pointerEvents = 'none';
  } else {
    targetX = mousePos.x + pandaOffsetX;
    targetY = mousePos.y + pandaOffsetY;
    pandaMascot.classList.remove('avoiding');
    pandaBodyWrapper.style.pointerEvents = 'auto';
  }
  
  const dx = targetX - pandaPos.x;
  const dy = targetY - pandaPos.y;
  
  pandaPos.x += dx * pandaSpeed;
  pandaPos.y += dy * pandaSpeed;
  
  if (dx < -1 && !pandaFacingLeft) {
    pandaFacingLeft = true;
    pandaMascot.querySelector('.panda-svg').classList.add('facing-left');
  } else if (dx > 1 && pandaFacingLeft) {
    pandaFacingLeft = false;
    pandaMascot.querySelector('.panda-svg').classList.remove('facing-left');
  }
  
  const size = 115;
  const clampedX = Math.min(Math.max(0, pandaPos.x), window.innerWidth - size);
  const clampedY = Math.min(Math.max(0, pandaPos.y), window.innerHeight - size);

  const tilt = Math.min(Math.max(dx * 0.12, -15), 15);
  pandaMascot.style.transform = `translate3d(${clampedX}px, ${clampedY}px, 0) rotate(${tilt}deg)`;
  
  requestAnimationFrame(updatePandaPosition);
}


let bubbleTimeout = null;

function triggerPandaBubble(message, duration = 4000) {
  if (bubbleTimeout) clearTimeout(bubbleTimeout);
  
  pandaBubble.textContent = message;
  pandaBubble.classList.add('show');
  
  bubbleTimeout = setTimeout(() => {
    pandaBubble.classList.remove('show');
  }, duration);
}

const cuteQuotes = [
  "You're doing great! Keep studying! 📚",
  "Panda loves learning new things! 🐼",
  "Have you hydrated today? 💧🌸",
  "I believe in you! 🧸✨",
  "Taking notes makes you smarter! 🧠💖",
  "Can I help you summarize? 📝",
  "A fresh pink day is a productive day! 🌸"
];

// ----------------------------------------
//  Panda Chat & Text Selection Explainer Logic
// ----------------------------------------

const chatWindow = $('#panda-chat-window');
const chatMessages = $('#chat-messages');
const chatInput = $('#chat-input');
const chatSendBtn = $('#chat-send-btn');
const chatCloseBtn = $('#chat-close-btn');
const chatMicBtn = $('#chat-mic-btn');
const chatVoiceToggle = $('#chat-voice-toggle');
const selectionTooltipBtn = $('#selection-tooltip-btn');

let chatHistory = [];
let lastSelectionText = '';

// ----------------------------------------
//  Panda Voice Feature (Speech Synthesis & Recognition)
//  Supports English + Hindi with auto language detection
// ----------------------------------------

let pandaVoiceEnabled = true;
let isSpeaking = false;
let isRecording = false;
let englishVoice = null;
let hindiVoice = null;
let speechRecognition = null;

// Initialize voice toggle state
chatVoiceToggle.classList.add('active');

// Detect if text contains Hindi (Devanagari script)
function isHindiText(text) {
  // Count Devanagari characters vs Latin characters
  const devanagariChars = (text.match(/[\u0900-\u097F]/g) || []).length;
  const latinChars = (text.match(/[a-zA-Z]/g) || []).length;
  
  // If significant Devanagari present, treat as Hindi
  // Also detect common Hinglish patterns (romanized Hindi words)
  const hinglishPatterns = /\b(kya|kaise|hai|haan|nahi|acha|theek|samjho|dekho|bolo|batao|padho|suno|matlab|yani|jaise|agar|toh|aur|mein|hum|tum|aap|yeh|woh|kuch|bahut|accha|chalo|karo|karna|hota|koi)\b/i;
  
  if (devanagariChars > 3) return true;
  if (devanagariChars > 0 && devanagariChars >= latinChars * 0.15) return true;
  if (hinglishPatterns.test(text) && devanagariChars === 0) return false; // Hinglish in Latin script — use English voice
  return false;
}

// Pick the best English female voice
function pickEnglishVoice() {
  const voices = speechSynthesis.getVoices();
  if (!voices.length) return null;
  
  const preferred = [
    'Microsoft Zira',
    'Google UK English Female',
    'Google US English',
    'Samantha',
    'Karen',
    'Moira',
    'Tessa',
    'Fiona',
    'Victoria',
    'Veena'
  ];
  
  for (const name of preferred) {
    const v = voices.find(voice => voice.name.includes(name));
    if (v) return v;
  }
  
  const femaleVoice = voices.find(v => 
    v.lang.startsWith('en') && 
    (v.name.toLowerCase().includes('female') || 
     v.name.toLowerCase().includes('zira') ||
     v.name.toLowerCase().includes('samantha'))
  );
  if (femaleVoice) return femaleVoice;
  
  const englishVoice = voices.find(v => v.lang.startsWith('en'));
  return englishVoice || null;
}

// Pick the best Hindi female voice
function pickHindiVoice() {
  const voices = speechSynthesis.getVoices();
  if (!voices.length) return null;
  
  // Preferred Hindi female voices (ordered by preference)
  const preferred = [
    'Microsoft Swara',       // Windows Hindi female — sweet & natural
    'Microsoft Kalpana',     // Windows Hindi female
    'Google हिन्दी',          // Chrome Hindi
    'Lekha',                 // Some systems
    'Neerja',                // Some systems
  ];
  
  for (const name of preferred) {
    const v = voices.find(voice => voice.name.includes(name));
    if (v) return v;
  }
  
  // Fallback: any Hindi voice
  const hindiVoice = voices.find(v => 
    v.lang.startsWith('hi') || v.lang === 'hi-IN'
  );
  if (hindiVoice) return hindiVoice;
  
  // Fallback: any Indian English voice (for Hinglish)
  const indianEnglish = voices.find(v => v.lang === 'en-IN');
  return indianEnglish || null;
}

// Load voices (they load asynchronously in some browsers)
function loadVoices() {
  englishVoice = pickEnglishVoice();
  hindiVoice = pickHindiVoice();
  
  // Debug: log available voices
  const voices = speechSynthesis.getVoices();
  const hindiVoices = voices.filter(v => v.lang.startsWith('hi') || v.lang === 'en-IN');
  if (hindiVoices.length) {
    console.log('🐼 Hindi/Indian voices found:', hindiVoices.map(v => `${v.name} (${v.lang})`).join(', '));
  }
  if (englishVoice) console.log('🐼 English voice:', englishVoice.name);
  if (hindiVoice) console.log('🐼 Hindi voice:', hindiVoice.name);
}

if (speechSynthesis.onvoiceschanged !== undefined) {
  speechSynthesis.onvoiceschanged = loadVoices;
}
loadVoices();

// Speak text as Panda (auto-detects Hindi vs English)
function pandaSpeak(text) {
  if (!pandaVoiceEnabled || !text) return;
  
  // Stop any current speech
  speechSynthesis.cancel();
  
  // Clean text for speech (remove emojis, markdown, code blocks)
  const cleanText = text
    .replace(/```[\s\S]*?```/g, ' code example ')
    .replace(/`[^`]+`/g, '')
    .replace(/[\u{1F600}-\u{1F9FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}\u{1F300}-\u{1F5FF}\u{1F680}-\u{1F6FF}\u{1F1E0}-\u{1F1FF}\u{2702}-\u{27B0}\u{FE0F}\u{200D}\u{20E3}\u{E0020}-\u{E007F}]/gu, '')
    .replace(/[#*_~>|\-]/g, '')
    .replace(/\n+/g, '. ')
    .replace(/\s+/g, ' ')
    .trim();
  
  if (!cleanText) return;
  
  // Truncate very long responses for speech
  const speechText = cleanText.length > 500 ? cleanText.substring(0, 500) + '... aur bhi bahut kuch hai.' : cleanText;
  
  const useHindi = isHindiText(speechText);
  
  const utterance = new SpeechSynthesisUtterance(speechText);
  
  // Refresh voices in case they loaded late
  if (!englishVoice) englishVoice = pickEnglishVoice();
  if (!hindiVoice) hindiVoice = pickHindiVoice();
  
  if (useHindi && hindiVoice) {
    utterance.voice = hindiVoice;
    utterance.lang = 'hi-IN';
    utterance.rate = 0.95;
    utterance.pitch = 1.3;
  } else {
    if (englishVoice) utterance.voice = englishVoice;
    utterance.lang = 'en-US';
    utterance.rate = 1.05;
    utterance.pitch = 1.35;
  }
  
  utterance.volume = 0.9;
  
  utterance.onstart = () => { isSpeaking = true; };
  utterance.onend = () => { isSpeaking = false; };
  utterance.onerror = () => { isSpeaking = false; };
  
  speechSynthesis.speak(utterance);
}

// Toggle voice on/off
chatVoiceToggle.addEventListener('click', () => {
  pandaVoiceEnabled = !pandaVoiceEnabled;
  chatVoiceToggle.classList.toggle('active', pandaVoiceEnabled);
  chatVoiceToggle.title = pandaVoiceEnabled ? 'Panda voice ON (click to mute)' : 'Panda voice OFF (click to unmute)';
  
  if (!pandaVoiceEnabled) {
    speechSynthesis.cancel();
    showToast('Panda voice muted 🔇');
  } else {
    showToast('Panda voice enabled 🔊');
    pandaSpeak('I can talk now! Yay!');
  }
});

// ----------------------------------------
//  Speech Recognition (Voice Input)
//  Uses en-IN to handle English, Hindi & Hinglish
// ----------------------------------------

const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;

if (SpeechRecognition) {
  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = false;
  speechRecognition.interimResults = false;
  speechRecognition.lang = 'en-IN'; // Indian English — handles Hindi/Hinglish well
  
  speechRecognition.onresult = (event) => {
    const transcript = event.results[0][0].transcript;
    if (transcript.trim()) {
      chatInput.value = transcript;
      sendChatMessage(transcript);
    }
  };
  
  speechRecognition.onstart = () => {
    isRecording = true;
    chatMicBtn.classList.add('recording');
    chatMicBtn.title = 'Listening... (click to stop)';
    chatInput.placeholder = '🎙️ Listening... (English / हिन्दी)';
  };
  
  speechRecognition.onend = () => {
    isRecording = false;
    chatMicBtn.classList.remove('recording');
    chatMicBtn.title = 'Click to speak to Pandy';
    chatInput.placeholder = 'Ask Pandy anything... 🌸';
  };
  
  speechRecognition.onerror = (event) => {
    isRecording = false;
    chatMicBtn.classList.remove('recording');
    chatMicBtn.title = 'Click to speak to Pandy';
    chatInput.placeholder = 'Ask Pandy anything... 🌸';
    if (event.error !== 'no-speech' && event.error !== 'aborted') {
      showToast('Could not hear you, try again 🎙️', 'error');
    }
  };
}

chatMicBtn.addEventListener('click', () => {
  if (!speechRecognition) {
    showToast('Voice input not supported in this browser 😿', 'error');
    return;
  }
  
  // Make sure chat is open
  if (chatWindow.style.display !== 'flex') {
    openPandaChat(false);
  }
  
  if (isRecording) {
    speechRecognition.stop();
  } else {
    speechRecognition.start();
    triggerPandaBubble('I\'m listening! बोलो! 🎙️🐼');
  }
});

// Toggle chat window open/close when clicking the panda
pandaBodyWrapper.addEventListener('click', (e) => {
  e.stopPropagation();
  const isOpen = chatWindow.style.display === 'flex';
  if (isOpen) {
    closePandaChat();
  } else {
    openPandaChat(false);
  }
});

function openPandaChat(isExplanation = false) {
  chatWindow.style.display = 'flex';
  
  const greetings = [
    "Hi there! Let's study together! 🐼🌸",
    "Hello! Ready to learn something new? 🐼✨",
    "Hey! Ask me anything, I'm here to help! 🐼🧸",
    "Hi! Let's make studying fun today! 🐼🧠"
  ];
  const greeting = greetings[Math.floor(Math.random() * greetings.length)];
  
  if (isExplanation) {
    triggerPandaBubble("Hi! Let's explain this concept! 🐼💡");
    appendChatBubble("Hi! Let's look at this concept together: 🐼💡", 'model');
  } else {
    triggerPandaBubble(greeting);
    appendChatBubble(greeting, 'model');
  }
  
  chatInput.focus();
  scrollToBottom();
}

function closePandaChat() {
  chatWindow.style.display = 'none';
  // Keep chat history so conversation persists across open/close
  triggerPandaBubble("Talk to you later! Bye! 🐼👋");
}

chatCloseBtn.addEventListener('click', closePandaChat);

// Prevent closing when clicking inside chat window
chatWindow.addEventListener('click', (e) => {
  e.stopPropagation();
});

// Scroll chat to bottom
function scrollToBottom() {
  chatMessages.scrollTop = chatMessages.scrollHeight;
}

// Append a message bubble to the chat window
function appendChatBubble(text, role, isLoading = false) {
  const bubble = document.createElement('div');
  bubble.className = `chat-bubble ${role}`;
  if (isLoading) {
    bubble.classList.add('loading');
    bubble.innerHTML = 'Thinking... 🐼💤';
  } else {
    // Render markdown for model responses, basic formatting for user
    if (role === 'model') {
      bubble.innerHTML = markdownToHtml(text);
    } else {
      bubble.innerHTML = text.replace(/\n/g, '<br>');
    }
  }
  chatMessages.appendChild(bubble);
  scrollToBottom();
  return bubble;
}

// Max history entries kept on the client side
const MAX_CLIENT_CHAT_HISTORY = 20; // Keep it lean to save API tokens

// Rate limiting — prevent rapid-fire requests
let lastChatSendTime = 0;
const CHAT_COOLDOWN_MS = 3000; // 3 second cooldown between messages

// Send message function
async function sendChatMessage(text, isExplanation = false, isRetry = false) {
  if (!text) return;
  
  // Rate limit check
  const now = Date.now();
  if (!isRetry && now - lastChatSendTime < CHAT_COOLDOWN_MS) {
    const waitSecs = Math.ceil((CHAT_COOLDOWN_MS - (now - lastChatSendTime)) / 1000);
    showToast(`Please wait ${waitSecs}s before sending again 🐼`, 'error');
    return;
  }
  lastChatSendTime = now;
  
  if (!isRetry) {
    if (!isExplanation) {
      appendChatBubble(text, 'user');
      chatInput.value = '';
    } else {
      openPandaChat(true);
      appendChatBubble(`Explain this: "${text}"`, 'user');
    }
  }

  const loadingBubble = isRetry 
    ? appendChatBubble('', 'model', true) 
    : appendChatBubble('', 'model', true);

  try {
    // Send only last 4 messages to save tokens
    const historyToSend = isRetry ? [] : chatHistory.slice(-4);

    const response = await fetch('/api/panda-chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: text,
        history: historyToSend,
        isExplanation
      })
    });

    const data = await response.json();

    if (!response.ok) {
      // If retryable and not already a retry, try once more with empty history
      if (data.retryable && !isRetry) {
        console.warn('Panda chat failed, retrying with fresh context...');
        loadingBubble.innerHTML = '<p>Let me try again... 🐼🔄</p>';
        chatHistory = []; // Clear stale history
        await new Promise(r => setTimeout(r, 2000)); // Brief pause
        loadingBubble.remove();
        return sendChatMessage(text, isExplanation, true);
      }
      throw new Error(data.error || 'Failed to communicate');
    }

    // Remove loading indicator and show actual reply
    loadingBubble.classList.remove('loading');
    loadingBubble.innerHTML = markdownToHtml(data.reply);
    
    // Save to local history state (capped)
    chatHistory.push({ role: 'user', text: isExplanation ? `Explain this: "${text}"` : text });
    chatHistory.push({ role: 'model', text: data.reply });
    // Keep history capped to prevent unbounded growth
    if (chatHistory.length > MAX_CLIENT_CHAT_HISTORY) {
      chatHistory = chatHistory.slice(-MAX_CLIENT_CHAT_HISTORY);
    }

    // Panda speaks the reply!
    pandaSpeak(data.reply);

    triggerPandaBubble("Check my explanation! 🐼🌸");
  } catch (error) {
    loadingBubble.classList.remove('loading');
    loadingBubble.innerHTML = `Oops! ${error.message}`;
    showToast('Failed to chat with panda mascot', 'error');
  }
}

// Click Send
chatSendBtn.addEventListener('click', () => {
  const val = chatInput.value.trim();
  if (val) sendChatMessage(val);
});

// Press Enter
chatInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    const val = chatInput.value.trim();
    if (val) sendChatMessage(val);
  }
});

// ----------------------------------------
//  Text Selection Explainer Tooltip
// ----------------------------------------

document.addEventListener('selectionchange', handleTextSelection);

function handleTextSelection() {
  const selection = window.getSelection();
  const selectedText = selection.toString().trim();

  // Only show tooltip if text is selected inside the notes card content
  if (selectedText.length > 3 && isSelectionInsideNotesContent(selection)) {
    lastSelectionText = selectedText;
    const range = selection.getRangeAt(0);
    const rect = range.getBoundingClientRect();

    // Position tooltip button above the middle of selection rect
    selectionTooltipBtn.style.display = 'block';
    selectionTooltipBtn.style.left = `${rect.left + window.scrollX + (rect.width / 2) - (selectionTooltipBtn.offsetWidth / 2)}px`;
    selectionTooltipBtn.style.top = `${rect.top + window.scrollY - 45}px`;
  } else {
    // If not clicking the button itself, hide it
    setTimeout(() => {
      // Small timeout to allow click event on selectionTooltipBtn to fire first
      if (window.getSelection().toString().trim() === '') {
        selectionTooltipBtn.style.display = 'none';
      }
    }, 150);
  }
}

function isSelectionInsideNotesContent(selection) {
  if (!selection.anchorNode) return false;
  let node = selection.anchorNode.parentNode;
  while (node) {
    if (node.id === 'notes-content') return true;
    node = node.parentNode;
  }
  return false;
}

// Click Explainer Tooltip button
selectionTooltipBtn.addEventListener('mousedown', (e) => {
  e.preventDefault(); // Prevent selection from clearing immediately
  e.stopPropagation();
  if (lastSelectionText) {
    sendChatMessage(lastSelectionText, true);
    selectionTooltipBtn.style.display = 'none';
    window.getSelection().removeAllRanges(); // Clear selection
  }
});

setTimeout(() => {
  triggerPandaBubble("Hi! Let's study together! 🐼🌸");
}, 1000);

requestAnimationFrame(updatePandaPosition);

// ----------------------------------------
//  Initialize App
// ----------------------------------------

window.addEventListener('load', () => {
  renderHistoryList();
  setTimeout(() => urlInput.focus(), 300);
});
