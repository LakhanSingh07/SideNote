import React, { useEffect, useMemo, useState, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { invoke } from '@tauri-apps/api/core';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import { getCurrentWindow, LogicalPosition, LogicalSize, availableMonitors, currentMonitor } from '@tauri-apps/api/window';
import {
  Plus, Search, Pin, Archive, Trash2, Settings, Minus, X,
  StickyNote, ChevronRight, Check, CheckSquare, Square,
  Moon, Sun, RefreshCw, Copy, Wifi, ShieldCheck, Folder, Sparkles, Move,
  Bold, Italic, Underline, Heading, List, Clock, Link as LinkIcon,
  Mic, RotateCcw, RotateCw, Bell, BellOff, Download, Upload, Volume2, Type
} from 'lucide-react';
import './style.css';
import appLogo from './assets/logo.png';

export function playReminderChime() {
  try {
    const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioContextClass) return;
    const ctx = new AudioContextClass();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.type = 'sine';
    osc.frequency.setValueAtTime(587.33, ctx.currentTime); // D5
    osc.frequency.setValueAtTime(880, ctx.currentTime + 0.12); // A5
    gain.gain.setValueAtTime(0.35, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.7);
    osc.start();
    osc.stop(ctx.currentTime + 0.7);
  } catch (e) {
    console.warn('Audio chime warning:', e);
  }
}

// 5 Curated Font Options (Android Roboto, Cute Cursive, Rounded Sans, Literary Serif, Typewriter)
export const NOTE_FONTS = [
  { id: 'roboto', name: 'Roboto', category: 'Android Default (Clean)', class: 'font-roboto', sample: 'Aa Bb 123 • Clean' },
  { id: 'caveat', name: 'Caveat', category: 'Cute Handwritten / Cursive', class: 'font-caveat', sample: 'Sticky Note Handwriting ✨' },
  { id: 'nunito', name: 'Nunito', category: 'Cute Rounded Sans', class: 'font-nunito', sample: 'Soft & Friendly 🌸' },
  { id: 'lora', name: 'Lora', category: 'Literary Serif', class: 'font-lora', sample: 'Editorial Journaling 🖋️' },
  { id: 'mono', name: 'JetBrains Mono', category: 'Typewriter / Code', class: 'font-mono', sample: 'const note = true;' },
];

// Note Data Model compatible with Android and Windows SQLite
export type Note = {
  id: string;
  title: string;
  body: string;
  color: number;
  isChecklist: boolean;
  updatedAt: number;
  pinned: boolean;
  archived: boolean;
  reminderAt: number;
};

// 6 Curated Pastel Note Colors matching Android Ui.kt
export const NOTE_COLORS = [
  { name: 'Soft Yellow', bg: '#FFF6B8', border: '#F3E692', ink: '#3F3714', darkBg: '#3E381C', darkInk: '#FFF6B8' },
  { name: 'Warm Peach', bg: '#FFDFC7', border: '#F3C4A5', ink: '#482D1B', darkBg: '#442B1A', darkInk: '#FFDFC7' },
  { name: 'Blush Pink', bg: '#FFD4DE', border: '#F0B5C4', ink: '#4A1E2B', darkBg: '#42202D', darkInk: '#FFD4DE' },
  { name: 'Mint Green', bg: '#D4F5DE', border: '#AEE0BD', ink: '#1B3D28', darkBg: '#1B3827', darkInk: '#D4F5DE' },
  { name: 'Sky Blue', bg: '#D6EBFC', border: '#B2D7F5', ink: '#1A364E', darkBg: '#1A2F45', darkInk: '#D6EBFC' },
  { name: 'Lavender', bg: '#EBE0FD', border: '#D1BDF5', ink: '#372352', darkBg: '#32254B', darkInk: '#EBE0FD' },
];

const params = new URLSearchParams(window.location.search);
const view = params.get('view') || 'main';
const targetNoteId = params.get('id');
const native = Boolean((window as any).__TAURI_INTERNALS__);
const EVENT_CHANGE = 'sidenote_changed';

// Placement helper
type Placement = { label: string; x: number; y: number; width: number; height: number };
const placementKey = (id: string) => 'note-' + id;
const logicalBounds = (m: { position: { x: number; y: number }; size: { width: number; height: number }; scaleFactor: number }) => ({
  x: m.position.x / m.scaleFactor,
  y: m.position.y / m.scaleFactor,
  width: m.size.width / m.scaleFactor,
  height: m.size.height / m.scaleFactor,
});

async function safePlacement(label: string): Promise<Placement | null> {
  try {
    return native ? await invoke<Placement | null>('get_window_placement', { label }) : null;
  } catch {
    return null;
  }
}

async function persistPlacement(label: string, width?: number, height?: number) {
  if (!native || !label.startsWith('note-')) return;
  try {
    const w = getCurrentWindow();
    const pos = await w.outerPosition();
    const size = await w.outerSize();
    const scale = (await currentMonitor())?.scaleFactor || 1;
    await invoke('save_window_placement', {
      placement: {
        label,
        x: pos.x / scale,
        y: pos.y / scale,
        width: width || size.width / scale,
        height: height || size.height / scale,
      },
    });
  } catch (e) {
    console.warn('Could not persist placement', e);
  }
}

async function clampPlacement(p: Placement): Promise<Placement> {
  if (!native) return p;
  try {
    const mons = await availableMonitors();
    if (!mons.length) return p;
    const rects = mons.map(logicalBounds);
    const r = rects.find(
      rect =>
        p.x + p.width / 2 >= rect.x &&
        p.x + p.width / 2 <= rect.x + rect.width &&
        p.y + p.height / 2 >= rect.y &&
        p.y + p.height / 2 <= rect.y + rect.height
    ) || rects[0];
    return {
      ...p,
      x: Math.max(r.x, Math.min(p.x, r.x + r.width - p.width)),
      y: Math.max(r.y, Math.min(p.y, r.y + r.height - p.height)),
    };
  } catch {
    return p;
  }
}

// Storage helpers
const DEMO_KEY = 'sidenote_demo_notes';
const defaultSampleNotes: Note[] = [
  {
    id: 'sample-1',
    title: '✨ Welcome to SideNote',
    body: 'Your intelligent desktop companion. Peek from the edge, float sticky notes, and stay focused.',
    color: 5, // Lavender
    isChecklist: false,
    updatedAt: Date.now() - 60000,
    pinned: true,
    archived: false,
    reminderAt: 0,
  },
  {
    id: 'sample-2',
    title: 'Grocery & Essentials',
    body: '[x] Almond milk\n[x] Sourdough bread\n[ ] Fresh avocados\n[ ] Matcha tea powder',
    color: 3, // Mint Green
    isChecklist: true,
    updatedAt: Date.now() - 120000,
    pinned: false,
    archived: false,
    reminderAt: 0,
  },
  {
    id: 'sample-3',
    title: 'Design Philosophy',
    body: 'Soft pastel cards, tactile sticky-note feel, and smart edge interactions.',
    color: 0, // Soft Yellow
    isChecklist: false,
    updatedAt: Date.now() - 180000,
    pinned: false,
    archived: false,
    reminderAt: 0,
  },
];

function loadLocalDemo(): Note[] {
  try {
    const raw = localStorage.getItem(DEMO_KEY);
    if (!raw) {
      localStorage.setItem(DEMO_KEY, JSON.stringify(defaultSampleNotes));
      return defaultSampleNotes;
    }
    return JSON.parse(raw);
  } catch {
    return defaultSampleNotes;
  }
}

async function loadNotes(): Promise<Note[]> {
  return native ? invoke<Note[]>('list_notes') : loadLocalDemo();
}

async function saveNote(n: Note) {
  if (native) {
    await invoke('upsert_note', { note: n });
  } else {
    const list = loadLocalDemo().filter(x => x.id !== n.id);
    localStorage.setItem(DEMO_KEY, JSON.stringify([n, ...list]));
  }
  localStorage.setItem(EVENT_CHANGE, String(Date.now()));
  window.dispatchEvent(new Event(EVENT_CHANGE));
}

async function deleteNote(id: string) {
  if (native) {
    await invoke('delete_note', { id });
  } else {
    const list = loadLocalDemo().filter(x => x.id !== id);
    localStorage.setItem(DEMO_KEY, JSON.stringify(list));
  }
  localStorage.setItem(EVENT_CHANGE, String(Date.now()));
  window.dispatchEvent(new Event(EVENT_CHANGE));
}

function createFreshNote(): Note {
  return {
    id: crypto.randomUUID(),
    title: '',
    body: '',
    color: Math.floor(Math.random() * NOTE_COLORS.length),
    isChecklist: false,
    updatedAt: Date.now(),
    pinned: false,
    archived: false,
    reminderAt: 0,
  };
}

async function spawnWindow(label: string, url: string, width: number, height: number, x?: number, y?: number) {
  if (!native) return false;
  const prior = await WebviewWindow.getByLabel(label);
  if (prior) {
    await prior.show();
    await prior.setFocus();
    return true;
  }
  const w = new WebviewWindow(label, {
    url,
    title: 'SideNote',
    width,
    height,
    x,
    y,
    decorations: false,
    transparent: true,
    alwaysOnTop: true,
    shadow: false,
    resizable: true,
    focus: true,
  });
  w.once('tauri://created', async () => {
    try {
      await (w as any).setShadow(false);
    } catch {}
  });
  w.once('tauri://error', e => console.error('Failed to spawn window', e));
  return true;
}

async function openFloatingNote(n: Note) {
  if (native) {
    const label = placementKey(n.id);
    const old = await safePlacement(label);
    const p = old ? await clampPlacement(old) : null;
    await spawnWindow(label, '/?view=note&id=' + encodeURIComponent(n.id), p?.width || 340, p?.height || 430, p?.x, p?.y);
  } else {
    // In web preview, fire custom event to open floating card
    window.dispatchEvent(new CustomEvent('sidenote_preview_float', { detail: n }));
  }
}

async function openLibraryWindow() {
  if (native) {
    const prior = await WebviewWindow.getByLabel('library');
    if (prior) {
      await prior.show();
      await prior.setFocus();
      return;
    }
    await spawnWindow('library', '/?view=library', 880, 620);
  } else {
    window.dispatchEvent(new CustomEvent('sidenote_preview_library'));
  }
}

// Hook to observe notes
function useNotesList() {
  const [notes, setNotes] = useState<Note[]>([]);
  useEffect(() => {
    let active = true;
    const reload = () =>
      loadNotes().then(items => {
        if (active) setNotes(items);
      });
    reload();
    const timer = setInterval(reload, 1200);
    window.addEventListener(EVENT_CHANGE, reload);
    return () => {
      active = false;
      clearInterval(timer);
      window.removeEventListener(EVENT_CHANGE, reload);
    };
  }, []);
  return notes;
}

// ==============================================================
// 1. BRAND BADGE COMPONENT
// ==============================================================
function BrandBadge() {
  return (
    <div className="dock-brand">
      <div className="dock-logo-badge" title="SideNote">
        <img src={appLogo} alt="SideNote" />
      </div>
      <span className="dock-title-text">
        SideNote<span className="dock-title-dot">.</span>
      </span>
    </div>
  );
}

// ==============================================================
// 2. PRIMARY EXPERIENCE: EDGE DOCK & PEEKING NOTES
// ==============================================================
function EdgeDock() {
  const notes = useNotesList();
  const [expanded, setExpanded] = useState(false);
  const [theme, setTheme] = useState<'light' | 'dark'>('light');
  const [dockSide, setDockSide] = useState<'right' | 'left'>(() => {
    return (localStorage.getItem('sidenote_dock_side') as 'right' | 'left') || 'right';
  });
  const [alarmBanner, setAlarmBanner] = useState<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const busyRef = useRef(false);

  // Background reminder watcher: monitors notes and triggers pleasant chime + notification
  useEffect(() => {
    const checkReminders = async () => {
      const now = Date.now();
      for (const n of notes) {
        if (n.reminderAt > 0 && n.reminderAt <= now && !n.archived) {
          playReminderChime();
          const alertTitle = n.title || 'Note reminder';
          setAlarmBanner(`⏰ Reminder: ${alertTitle}`);
          setTimeout(() => setAlarmBanner(null), 7000);
          // Mark reminder as triggered to avoid looping
          await saveNote({ ...n, reminderAt: 0, updatedAt: Date.now() });
          await openFloatingNote(n);
        }
      }
    };
    const interval = setInterval(checkReminders, 8000);
    return () => clearInterval(interval);
  }, [notes]);

  // Listen for dock side changes from LibraryView
  useEffect(() => {
    const handleSideChange = () => {
      const side = (localStorage.getItem('sidenote_dock_side') as 'right' | 'left') || 'right';
      setDockSide(side);
      void adjustDockWidth(false, side);
    };
    window.addEventListener('sidenote_dock_side_changed', handleSideChange);
    return () => window.removeEventListener('sidenote_dock_side_changed', handleSideChange);
  }, []);

  // Filter 4 recent unarchived notes for peeking
  const peekingNotes = useMemo(() => {
    return notes
      .filter(n => !n.archived)
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
      .slice(0, 4);
  }, [notes]);

  // Position at screen edge (left or right) and handle smooth resize
  const adjustDockWidth = async (open: boolean, sideOverride?: 'right' | 'left') => {
    setExpanded(open);
    if (!native || busyRef.current) return;
    busyRef.current = true;
    try {
      const w = getCurrentWindow();
      const mon = await currentMonitor();
      if (!mon) return;
      const scale = mon.scaleFactor || 1;
      const screenWidth = mon.size.width / scale;
      const screenHeight = mon.size.height / scale;
      const pos = await w.outerPosition();
      const curY = pos.y / scale;
      const targetWidth = open ? 290 : 58;
      const targetHeight = 490;
      const currentSide = sideOverride || dockSide;
      
      const targetX = currentSide === 'left' 
        ? (mon.position.x / scale) 
        : (mon.position.x / scale) + screenWidth - targetWidth;
      const targetY = Math.max(50, Math.min(curY, screenHeight - targetHeight - 40));

      await w.setSize(new LogicalSize(targetWidth, targetHeight));
      await w.setPosition(new LogicalPosition(targetX, targetY));
    } catch (e) {
      console.warn('Dock move error', e);
    } finally {
      busyRef.current = false;
    }
  };

  const handleMouseEnter = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    if (!expanded) void adjustDockWidth(true);
  };

  const handleMouseLeave = () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(() => {
      void adjustDockWidth(false);
    }, 350);
  };

  useEffect(() => {
    void adjustDockWidth(false);
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, [dockSide]);

  const handleCreateNew = async (e: React.MouseEvent) => {
    e.stopPropagation();
    const fresh = createFreshNote();
    await saveNote(fresh);
    await openFloatingNote(fresh);
    void adjustDockWidth(false);
  };

  const toggleTheme = (e: React.MouseEvent) => {
    e.stopPropagation();
    const next = theme === 'light' ? 'dark' : 'light';
    setTheme(next);
    document.documentElement.setAttribute('data-theme', next);
  };

  return (
    <div className={`dock-container ${dockSide === 'left' ? 'is-left' : ''}`} data-theme={theme}>
      {alarmBanner && (
        <div className="desktop-toast-banner" onClick={() => setAlarmBanner(null)}>
          <Bell size={15} color="#FFD43B" />
          <span>{alarmBanner}</span>
        </div>
      )}

      <aside
        className={`dock-rail ${dockSide === 'left' ? 'is-left' : ''} ${expanded ? 'is-expanded' : ''}`}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        {/* Header Handle */}
        <header className="dock-header" onClick={() => void adjustDockWidth(!expanded)}>
          <BrandBadge />
          <button className="dock-collapse-btn" title="Collapse" onClick={() => void adjustDockWidth(false)}>
            <ChevronRight size={16} style={{ transform: dockSide === 'left' ? (expanded ? 'rotate(180deg)' : 'none') : (expanded ? 'none' : 'rotate(180deg)') }} />
          </button>
        </header>

        {/* Peeking Slivers / Notes Preview */}
        <div className="dock-chips-list">
          {peekingNotes.map(n => {
            const colorMeta = NOTE_COLORS[n.color] || NOTE_COLORS[0];
            const bg = theme === 'dark' ? colorMeta.darkBg : colorMeta.bg;
            const ink = theme === 'dark' ? colorMeta.darkInk : colorMeta.ink;

            return (
              <div
                key={n.id}
                className="peek-sliver"
                style={{ background: bg, color: ink, borderColor: colorMeta.border }}
                onClick={async () => {
                  await openFloatingNote(n);
                  void adjustDockWidth(false);
                }}
                title={n.title || 'Untitled Note'}
              >
                {!expanded ? (
                  <>
                    {n.isChecklist ? <CheckSquare size={14} /> : <StickyNote size={14} />}
                    {n.pinned && <div className="peek-indicator-dot" />}
                    {n.reminderAt > 0 && <div className="peek-indicator-dot" style={{ background: '#FF4757', top: 4 }} />}
                  </>
                ) : (
                  <>
                    <div className="peek-card-header">
                      <span className="peek-card-tag">{n.isChecklist ? 'Checklist' : 'Note'}</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        {n.reminderAt > 0 && <Bell size={11} color="var(--brand-violet)" />}
                        {n.pinned && <Pin size={12} fill="currentColor" />}
                      </div>
                    </div>
                    <h4 className="peek-card-title">{n.title || 'Untitled note'}</h4>
                    <p className="peek-card-snippet">
                      {n.body ? n.body.replace(/\[[ x]\] /g, '') : 'Tap to open card...'}
                    </p>
                  </>
                )}
              </div>
            );
          })}

          {peekingNotes.length === 0 && expanded && (
            <div style={{ padding: '20px 10px', textAlign: 'center', fontSize: 12, opacity: 0.6 }}>
              No notes yet. Click + to create your first sticky note!
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <footer className="dock-footer">
          <button className="dock-add-btn" title="Create floating note" onClick={handleCreateNew}>
            <Plus size={20} strokeWidth={2.5} />
          </button>
          <button className="dock-action-btn" title="All notes & sync" onClick={openLibraryWindow}>
            <Folder size={17} />
          </button>
          <button className="dock-action-btn" title="Toggle theme" onClick={toggleTheme}>
            {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
          </button>
        </footer>
      </aside>
    </div>
  );
}

// ==============================================================
// 3. FLOATING CUTE STICKY NOTE CARD
// ==============================================================
// ==============================================================
// 3. FLOATING CUTE STICKY NOTE CARD (WITH FULL EDITING SUITE)
// ==============================================================
function FloatingNoteCard({ noteId }: { noteId?: string }) {
  const effectiveId = noteId || targetNoteId;
  const notes = useNotesList();
  const note = notes.find(n => n.id === effectiveId);

  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [isChecklist, setIsChecklist] = useState(false);
  const [isTextMode, setIsTextMode] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [color, setColor] = useState(0);
  const [reminderAt, setReminderAt] = useState(0);
  const [newItemText, setNewItemText] = useState('');

  // Modals & Popups
  const [showReminderModal, setShowReminderModal] = useState(false);
  const [showLinkModal, setShowLinkModal] = useState(false);
  const [showFontModal, setShowFontModal] = useState(false);
  const [selectedFont, setSelectedFont] = useState<string>(() => {
    return localStorage.getItem('sidenote_font_' + (effectiveId || '')) || localStorage.getItem('sidenote_default_font') || 'roboto';
  });
  const [linkTitleInput, setLinkTitleInput] = useState('');
  const [linkUrlInput, setLinkUrlInput] = useState('https://');
  const [customReminderInput, setCustomReminderInput] = useState('');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [saveStatus, setSaveStatus] = useState<'Saved' | 'Saving…'>('Saved');

  // Undo / Redo history
  const [history, setHistory] = useState<string[]>([]);
  const [historyIdx, setHistoryIdx] = useState<number>(-1);

  // Voice speech-to-text dictation
  const [isListening, setIsListening] = useState(false);
  const recognitionRef = useRef<any>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const currentFontObj = NOTE_FONTS.find(f => f.id === selectedFont) || NOTE_FONTS[0];
  const fontClass = currentFontObj.class;

  const handleSelectFont = (fontId: string) => {
    setSelectedFont(fontId);
    if (effectiveId) {
      localStorage.setItem('sidenote_font_' + effectiveId, fontId);
    }
    setShowFontModal(false);
    const found = NOTE_FONTS.find(f => f.id === fontId);
    showToast(`Font set to ${found?.name || fontId}`);
  };

  useEffect(() => {
    if (note) {
      setTitle(note.title);
      setBody(note.body);
      setIsChecklist(note.isChecklist);
      setPinned(note.pinned);
      setColor(note.color);
      setReminderAt(note.reminderAt || 0);

      // Initialize history stack on first note load
      if (history.length === 0) {
        setHistory([note.body]);
        setHistoryIdx(0);
      }
    }
  }, [note?.id]);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  // Push new state to history (clamp to 50 items)
  const pushHistory = (newBody: string) => {
    if (historyIdx >= 0 && history[historyIdx] === newBody) return;
    const nextHistory = [...history.slice(0, historyIdx + 1), newBody].slice(-50);
    setHistory(nextHistory);
    setHistoryIdx(nextHistory.length - 1);
  };

  const handleUndo = () => {
    if (historyIdx > 0) {
      const prevIdx = historyIdx - 1;
      const prevText = history[prevIdx];
      setHistoryIdx(prevIdx);
      setBody(prevText);
      void updateFields({ body: prevText });
    }
  };

  const handleRedo = () => {
    if (historyIdx < history.length - 1) {
      const nextIdx = historyIdx + 1;
      const nextText = history[nextIdx];
      setHistoryIdx(nextIdx);
      setBody(nextText);
      void updateFields({ body: nextText });
    }
  };

  // Persist placement on move
  useEffect(() => {
    if (!native || !effectiveId) return;
    let timeout: ReturnType<typeof setTimeout> | null = null;
    let unlisten: undefined | (() => void);
    let active = true;

    getCurrentWindow()
      .onMoved(() => {
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(() => void persistPlacement(placementKey(effectiveId), 340, 430), 400);
      })
      .then(fn => {
        if (active) unlisten = fn;
        else fn();
      })
      .catch(console.warn);

    return () => {
      active = false;
      if (timeout) clearTimeout(timeout);
      unlisten?.();
    };
  }, [effectiveId]);

  // Card-level reminder alarm watcher
  useEffect(() => {
    if (reminderAt > 0 && reminderAt <= Date.now()) {
      playReminderChime();
      showToast(`⏰ Reminder: ${title || 'Note reminder'}`);
      setReminderAt(0);
      void updateFields({ reminderAt: 0 });
    }
  }, [reminderAt]);

  if (!note) {
    return <div style={{ padding: 20, fontSize: 13, color: '#888' }}>Loading sticky note...</div>;
  }

  const colorMeta = NOTE_COLORS[color] || NOTE_COLORS[0];

  const updateFields = async (updates: Partial<Note>) => {
    setSaveStatus('Saving…');
    const updated: Note = {
      ...note,
      ...updates,
      updatedAt: Date.now(),
    };
    await saveNote(updated);
    setTimeout(() => setSaveStatus('Saved'), 300);
  };

  const handleTitleBlur = () => {
    if (title !== note.title) void updateFields({ title });
  };

  const handleBodyBlur = () => {
    if (body !== note.body) {
      pushHistory(body);
      void updateFields({ body });
    }
  };

  const togglePin = async () => {
    const next = !pinned;
    setPinned(next);
    if (native) {
      try {
        await getCurrentWindow().setAlwaysOnTop(next);
      } catch (e) {
        console.warn(e);
      }
    }
    await updateFields({ pinned: next });
    showToast(next ? 'Note pinned to top' : 'Note unpinned');
  };

  const handleMinimize = async () => {
    if (native) {
      await persistPlacement(placementKey(note.id));
      await getCurrentWindow().close();
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }));
    }
  };

  const handleArchive = async () => {
    await updateFields({ archived: true });
    await handleMinimize();
  };

  // Text formatting insertion helper
  const insertFormatting = (prefix: string, suffix: string = '') => {
    const el = textareaRef.current;
    if (!el) {
      const next = body + prefix + suffix;
      setBody(next);
      pushHistory(next);
      void updateFields({ body: next });
      return;
    }
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    const val = el.value;
    const selected = val.substring(start, end);
    const fallback = prefix === '• ' || prefix === '[ ] ' || prefix === '### ' ? '' : 'text';
    const replacement = `${prefix}${selected || fallback}${suffix}`;
    const nextVal = val.substring(0, start) + replacement + val.substring(end);
    setBody(nextVal);
    pushHistory(nextVal);
    void updateFields({ body: nextVal });
    setTimeout(() => {
      el.focus();
      const newPos = start + replacement.length;
      el.setSelectionRange(newPos, newPos);
    }, 10);
  };

  // Keyboard shortcuts (Ctrl+B, Ctrl+I, Ctrl+U, Ctrl+Z, Ctrl+Y, Tab)
  const handleTextareaKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.ctrlKey || e.metaKey) {
      if (e.key === 'b' || e.key === 'B') {
        e.preventDefault();
        insertFormatting('**', '**');
      } else if (e.key === 'i' || e.key === 'I') {
        e.preventDefault();
        insertFormatting('*', '*');
      } else if (e.key === 'u' || e.key === 'U') {
        e.preventDefault();
        insertFormatting('<u>', '</u>');
      } else if (e.key === 'z' || e.key === 'Z') {
        e.preventDefault();
        handleUndo();
      } else if (e.key === 'y' || e.key === 'Y') {
        e.preventDefault();
        handleRedo();
      }
    } else if (e.key === 'Tab') {
      e.preventDefault();
      insertFormatting('  ');
    }
  };

  // Copy note body to clipboard
  const handleCopyNote = async () => {
    try {
      await navigator.clipboard.writeText(body);
      showToast('Note copied to clipboard!');
    } catch {
      showToast('Copy failed');
    }
  };

  // Voice Speech-to-Text dictation toggle
  const toggleDictation = () => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      showToast('Speech recognition not supported in this browser.');
      return;
    }
    if (isListening && recognitionRef.current) {
      recognitionRef.current.stop();
      setIsListening(false);
      showToast('Voice dictation stopped');
      return;
    }
    try {
      const recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = 'en-US';
      recognition.onresult = (event: any) => {
        let final = '';
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          if (event.results[i].isFinal) {
            final += event.results[i][0].transcript;
          }
        }
        if (final) {
          setBody(prev => {
            const sep = prev.length > 0 && !prev.endsWith(' ') && !prev.endsWith('\n') ? ' ' : '';
            const next = prev + sep + final.trim();
            pushHistory(next);
            void updateFields({ body: next });
            return next;
          });
        }
      };
      recognition.onerror = () => setIsListening(false);
      recognition.onend = () => setIsListening(false);
      recognition.start();
      recognitionRef.current = recognition;
      setIsListening(true);
      showToast('Listening... Speak now 🎙️');
    } catch (err) {
      console.warn('Speech error:', err);
      setIsListening(false);
    }
  };

  // Checklist parser & serializer
  const parseChecklist = (raw: string) => {
    const lines = raw.split('\n').filter(l => l.trim().length > 0);
    return lines.map((line, idx) => {
      const isDone = line.startsWith('[x] ') || line.startsWith('[X] ');
      const text = line.replace(/^\[[ xX]\] /, '');
      return { id: idx, isDone, text };
    });
  };

  const checklistItems = parseChecklist(body);

  const toggleChecklistItem = async (index: number) => {
    const items = [...checklistItems];
    items[index].isDone = !items[index].isDone;
    const newBody = items.map(item => `[${item.isDone ? 'x' : ' '}] ${item.text}`).join('\n');
    setBody(newBody);
    pushHistory(newBody);
    await updateFields({ body: newBody });
  };

  const handleEditChecklistItem = (index: number, newText: string) => {
    const items = [...checklistItems];
    items[index].text = newText;
    const newBody = items.map(item => `[${item.isDone ? 'x' : ' '}] ${item.text}`).join('\n');
    setBody(newBody);
  };

  const handleFinishEditChecklistItem = async () => {
    pushHistory(body);
    await updateFields({ body });
  };

  const addChecklistItem = async () => {
    if (!newItemText.trim()) return;
    const newBody = body ? `${body}\n[ ] ${newItemText.trim()}` : `[ ] ${newItemText.trim()}`;
    setBody(newBody);
    setNewItemText('');
    pushHistory(newBody);
    await updateFields({ body: newBody });
  };

  const deleteChecklistItem = async (index: number) => {
    const items = checklistItems.filter((_, i) => i !== index);
    const newBody = items.map(item => `[${item.isDone ? 'x' : ' '}] ${item.text}`).join('\n');
    setBody(newBody);
    pushHistory(newBody);
    await updateFields({ body: newBody });
  };

  // Reminder preset setter
  const setReminderOffset = async (ms: number, label: string) => {
    const target = Date.now() + ms;
    setReminderAt(target);
    setShowReminderModal(false);
    await updateFields({ reminderAt: target });
    showToast(`Reminder set for ${label}`);
  };

  const setTomorrowMorningReminder = async () => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    d.setHours(9, 0, 0, 0);
    const target = d.getTime();
    setReminderAt(target);
    setShowReminderModal(false);
    await updateFields({ reminderAt: target });
    showToast('Reminder set for Tomorrow 9:00 AM');
  };

  const handleCustomReminderSubmit = async () => {
    if (!customReminderInput) return;
    const target = new Date(customReminderInput).getTime();
    if (isNaN(target) || target <= Date.now()) {
      showToast('Please pick a future date and time');
      return;
    }
    setReminderAt(target);
    setShowReminderModal(false);
    await updateFields({ reminderAt: target });
    showToast('Custom reminder scheduled!');
  };

  const handleClearReminder = async () => {
    setReminderAt(0);
    setShowReminderModal(false);
    await updateFields({ reminderAt: 0 });
    showToast('Reminder cleared');
  };

  // Link dialog submit
  const handleInsertLink = () => {
    const url = linkUrlInput.trim();
    const titleText = linkTitleInput.trim();
    if (url && url !== 'https://') {
      const formatted = titleText ? `[${titleText}](${url})` : url;
      insertFormatting(formatted);
      setShowLinkModal(false);
      setLinkTitleInput('');
      setLinkUrlInput('https://');
    }
  };

  const handleDragMouseDown = (e: React.MouseEvent) => {
    if (e.button === 0) {
      const target = e.target as HTMLElement;
      if (!target.closest('button, input, textarea, .color-swatch-dot, .preset-btn, [data-no-drag]')) {
        if (native) {
          getCurrentWindow().startDragging().catch(console.warn);
        }
      }
    }
  };

  // Live Statistics Calculation
  const totalTasks = checklistItems.length;
  const completedTasks = checklistItems.filter(i => i.isDone).length;
  const taskPct = totalTasks > 0 ? Math.round((completedTasks / totalTasks) * 100) : 0;
  const wordCount = body.trim() ? body.trim().split(/\s+/).length : 0;
  const charCount = body.length;

  return (
    <div className="floating-card-wrapper" onMouseDown={handleDragMouseDown}>
      {toastMessage && (
        <div className="desktop-toast-banner" onClick={() => setToastMessage(null)}>
          <Sparkles size={14} color="#FFF" />
          <span>{toastMessage}</span>
        </div>
      )}

      <div
        className="floating-sticky-note"
        style={{
          background: colorMeta.bg,
          color: colorMeta.ink,
          borderColor: colorMeta.border,
        }}
      >
        {/* Top Accent Tape with Drag Region */}
        <div className="sticky-top-tape" data-tauri-drag-region onMouseDown={handleDragMouseDown}>
          <div className="sticky-badge-group" data-tauri-drag-region onMouseDown={handleDragMouseDown}>
            <img src={appLogo} alt="" style={{ width: 15, height: 15, objectFit: 'contain', pointerEvents: 'none' }} />
            <span className="sticky-badge" style={{ pointerEvents: 'none' }}>
              {isChecklist ? 'Checklist' : 'Sticky Note'}
            </span>
          </div>

          <div className="sticky-actions" data-no-drag onMouseDown={e => e.stopPropagation()}>
            {/* Reminder Bell */}
            <button
              className={`sticky-icon-btn ${reminderAt > Date.now() ? 'is-active' : ''}`}
              title={reminderAt > Date.now() ? 'Reminder active (Click to manage)' : 'Set note reminder'}
              onClick={() => setShowReminderModal(true)}
            >
              <Bell size={13} fill={reminderAt > Date.now() ? 'currentColor' : 'none'} />
            </button>

            {/* Pin Toggle */}
            <button
              className={`sticky-icon-btn ${pinned ? 'is-active' : ''}`}
              title={pinned ? 'Unpin from top' : 'Pin on top'}
              onClick={togglePin}
            >
              <Pin size={13} fill={pinned ? 'currentColor' : 'none'} />
            </button>

            {/* Minimize to Edge Dock */}
            <button className="sticky-icon-btn" title="Fold to edge dock" onClick={handleMinimize}>
              <Minus size={14} />
            </button>

            {/* Close / Archive */}
            <button className="sticky-icon-btn" title="Archive note" onClick={handleArchive}>
              <X size={14} />
            </button>
          </div>
        </div>

        {/* Live Note Statistics Strip */}
        <div className="sticky-stats-strip">
          <div className="sticky-stats-left">
            {isChecklist ? (
              <span>{completedTasks} / {totalTasks} tasks completed ({taskPct}%)</span>
            ) : (
              <span>{wordCount} words · {charCount} characters</span>
            )}
            {reminderAt > Date.now() && (
              <span className="reminder-chip-badge" title="Active reminder">
                <Clock size={10} />
                {new Date(reminderAt).toLocaleDateString([], { month: 'short', day: 'numeric' })},{' '}
                {new Date(reminderAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
              </span>
            )}
          </div>
          <span className="sticky-stats-status">{saveStatus}</span>
        </div>

        {/* Checklist Toggle & View Mode Row */}
        <div className="sticky-control-row">
          <label className="control-toggle-label">
            <input
              type="checkbox"
              checked={isChecklist}
              onChange={async e => {
                const next = e.target.checked;
                setIsChecklist(next);
                if (next) {
                  // Format raw lines into checklist items if not already formatted
                  const lines = body.split('\n').map(l => (l.trim() && !l.startsWith('[ ] ') && !l.startsWith('[x] ') ? `[ ] ${l}` : l));
                  const newB = lines.join('\n');
                  setBody(newB);
                  pushHistory(newB);
                  await updateFields({ isChecklist: next, body: newB });
                } else {
                  await updateFields({ isChecklist: next });
                }
              }}
            />
            <span>To-do checklist</span>
          </label>

          {isChecklist && (
            <button
              className="control-mode-pill"
              title="Toggle interactive list items vs raw markdown edit"
              onClick={() => setIsTextMode(!isTextMode)}
            >
              {isTextMode ? 'Interactive View' : 'Raw Text Mode'}
            </button>
          )}
        </div>

        {/* Rich Formatting Toolbar */}
        <div className="sticky-toolbar-row" data-no-drag onMouseDown={e => e.stopPropagation()}>
          <button
            className="tool-btn"
            title="Undo (Ctrl+Z)"
            disabled={historyIdx <= 0}
            onClick={handleUndo}
          >
            <RotateCcw size={13} />
          </button>
          <button
            className="tool-btn"
            title="Redo (Ctrl+Y)"
            disabled={historyIdx >= history.length - 1}
            onClick={handleRedo}
          >
            <RotateCw size={13} />
          </button>

          <div className="tool-divider" />

          <button className="tool-btn" title="Bold (Ctrl+B)" onClick={() => insertFormatting('**', '**')}>
            <Bold size={13} />
          </button>
          <button className="tool-btn" title="Italic (Ctrl+I)" onClick={() => insertFormatting('*', '*')}>
            <Italic size={13} />
          </button>
          <button className="tool-btn" title="Underline (Ctrl+U)" onClick={() => insertFormatting('<u>', '</u>')}>
            <Underline size={13} />
          </button>
          <button className="tool-btn" title="Heading" onClick={() => insertFormatting('### ')}>
            <Heading size={13} />
          </button>
          <button className="tool-btn" title="Bullet List" onClick={() => insertFormatting('• ')}>
            <List size={13} />
          </button>
          <button className="tool-btn" title="To-do Task" onClick={() => insertFormatting('[ ] ')}>
            <CheckSquare size={13} />
          </button>
          <button
            className="tool-btn"
            title="Timestamp"
            onClick={() => {
              const stamp = new Date().toLocaleDateString([], { month: 'short', day: 'numeric' }) + ', ' +
                new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
              insertFormatting(stamp);
            }}
          >
            <Clock size={13} />
          </button>

          <div className="tool-divider" />

          <button
            className="tool-btn"
            title="Insert Web Link"
            onClick={async () => {
              const el = textareaRef.current;
              const sel = el && el.selectionStart !== el.selectionEnd ? el.value.substring(el.selectionStart, el.selectionEnd) : '';
              if (sel) setLinkTitleInput(sel);
              try {
                const clip = await navigator.clipboard.readText();
                if (clip.startsWith('http://') || clip.startsWith('https://')) {
                  setLinkUrlInput(clip.trim());
                }
              } catch {}
              setShowLinkModal(true);
            }}
          >
            <LinkIcon size={13} />
          </button>
          <button className="tool-btn" title="Copy note text" onClick={handleCopyNote}>
            <Copy size={13} />
          </button>
          <button
            className={`tool-btn ${isListening ? 'is-recording' : ''}`}
            title={isListening ? 'Stop recording voice' : 'Dictate note by voice'}
            onClick={toggleDictation}
          >
            <Mic size={13} />
            {isListening && <span>Rec</span>}
          </button>

          <div className="tool-divider" />

          <button
            className="tool-btn font-selector-btn"
            title="Choose Font Style (Roboto, Cute Cursive, Rounded Sans, Serif, Mono)"
            onClick={() => setShowFontModal(true)}
          >
            <Type size={13} />
            <span style={{ fontSize: 10.5 }}>{currentFontObj.name}</span>
          </button>
        </div>

        {/* Content Area */}
        <div className="sticky-body-area">
          <input
            className={`sticky-title-input ${fontClass}`}
            placeholder="Title..."
            value={title}
            onChange={e => setTitle(e.target.value)}
            onBlur={handleTitleBlur}
            maxLength={2000}
          />

          {!isChecklist || isTextMode ? (
            <textarea
              ref={textareaRef}
              className={`sticky-textarea ${fontClass}`}
              placeholder={isChecklist ? '[ ] First task…\n[ ] Second task…' : 'Jot down a quick thought…'}
              value={body}
              onChange={e => setBody(e.target.value)}
              onBlur={handleBodyBlur}
              onKeyDown={handleTextareaKeyDown}
            />
          ) : (
            <div className="checklist-container">
              {checklistItems.map((item, idx) => (
                <div key={idx} className="checklist-item-row">
                  <div
                    className={`checklist-checkbox ${item.isDone ? 'is-checked' : ''}`}
                    onClick={() => void toggleChecklistItem(idx)}
                    title={item.isDone ? 'Mark incomplete' : 'Mark complete'}
                  >
                    {item.isDone && <Check size={12} strokeWidth={3} />}
                  </div>
                  <input
                    className={`checklist-item-text ${item.isDone ? 'is-done' : ''} ${fontClass}`}
                    value={item.text}
                    onChange={e => handleEditChecklistItem(idx, e.target.value)}
                    onBlur={handleFinishEditChecklistItem}
                  />
                  <button
                    className="checklist-delete-btn"
                    title="Delete item"
                    onClick={() => void deleteChecklistItem(idx)}
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}

              <div className="checklist-add-row">
                <input
                  className={`checklist-add-input ${fontClass}`}
                  placeholder="＋ Add a task… (Enter to add)"
                  value={newItemText}
                  onChange={e => setNewItemText(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') void addChecklistItem();
                  }}
                />
                <button className="checklist-add-btn" onClick={addChecklistItem}>
                  Add
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Footer with Pastel Color Swatches */}
        <div className="sticky-footer-bar" data-tauri-drag-region>
          <div className="color-swatches-row">
            {NOTE_COLORS.map((c, i) => (
              <div
                key={i}
                className={`color-swatch-dot ${color === i ? 'is-selected' : ''}`}
                style={{ background: c.bg }}
                title={c.name}
                onClick={async () => {
                  setColor(i);
                  await updateFields({ color: i });
                }}
              />
            ))}
          </div>

          <span className="sticky-footer-meta">
            {new Date(note.updatedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
          </span>
        </div>

        {/* 1. Reminder Preset Modal Overlay */}
        {showReminderModal && (
          <div className="note-popup-overlay" onClick={() => setShowReminderModal(false)}>
            <div className="note-popup-card" onClick={e => e.stopPropagation()}>
              <h3 className="popup-title">
                <Bell size={16} color="var(--brand-violet)" /> Note Reminder
              </h3>
              <div className="popup-preset-list">
                <button className="preset-btn" onClick={() => void setReminderOffset(15 * 60 * 1000, 'In 15 minutes')}>
                  <span>In 15 minutes</span>
                  <Clock size={12} opacity={0.6} />
                </button>
                <button className="preset-btn" onClick={() => void setReminderOffset(60 * 60 * 1000, 'In 1 hour')}>
                  <span>In 1 hour</span>
                  <Clock size={12} opacity={0.6} />
                </button>
                <button className="preset-btn" onClick={() => void setTomorrowMorningReminder()}>
                  <span>Tomorrow (9:00 AM)</span>
                  <Clock size={12} opacity={0.6} />
                </button>
              </div>

              <div style={{ marginTop: 4 }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink-secondary)', display: 'block', marginBottom: 4 }}>
                  Pick Date & Time
                </span>
                <input
                  type="datetime-local"
                  className="popup-input"
                  value={customReminderInput}
                  onChange={e => setCustomReminderInput(e.target.value)}
                />
              </div>

              <div className="popup-actions">
                {reminderAt > 0 && (
                  <button className="popup-btn-danger" onClick={handleClearReminder}>
                    Clear
                  </button>
                )}
                <button className="popup-btn-secondary" onClick={() => setShowReminderModal(false)}>
                  Cancel
                </button>
                <button className="popup-btn-primary" onClick={handleCustomReminderSubmit}>
                  Set Alarm
                </button>
              </div>
            </div>
          </div>
        )}

        {/* 2. Web Link Insertion Modal Overlay */}
        {showLinkModal && (
          <div className="note-popup-overlay" onClick={() => setShowLinkModal(false)}>
            <div className="note-popup-card" onClick={e => e.stopPropagation()}>
              <h3 className="popup-title">
                <LinkIcon size={16} color="var(--brand-violet)" /> Insert Web Link
              </h3>
              <div>
                <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink-secondary)', display: 'block', marginBottom: 4 }}>
                  Link Text (Optional)
                </span>
                <input
                  className="popup-input"
                  placeholder="e.g. My Website"
                  value={linkTitleInput}
                  onChange={e => setLinkTitleInput(e.target.value)}
                />
              </div>
              <div>
                <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink-secondary)', display: 'block', marginBottom: 4 }}>
                  Destination URL
                </span>
                <input
                  className="popup-input"
                  placeholder="https://example.com"
                  value={linkUrlInput}
                  onChange={e => setLinkUrlInput(e.target.value)}
                />
              </div>
              <div className="popup-actions">
                <button className="popup-btn-secondary" onClick={() => setShowLinkModal(false)}>
                  Cancel
                </button>
                <button className="popup-btn-primary" onClick={handleInsertLink}>
                  Insert
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Font Style Selection Popup */}
        {showFontModal && (
          <div className="note-popup-overlay" onClick={() => setShowFontModal(false)}>
            <div className="note-popup-card" onClick={e => e.stopPropagation()} style={{ maxWidth: 310 }}>
              <h3 className="popup-title">
                <Type size={16} color="var(--brand-violet)" /> Choose Note Font
              </h3>
              <p style={{ margin: '0 0 10px 0', fontSize: 11, color: 'var(--ink-secondary)', lineHeight: 1.4 }}>
                Pick a font style for this sticky note (cute cursive, serif, clean sans, etc.):
              </p>
              <div className="font-preset-list">
                {NOTE_FONTS.map(f => (
                  <div
                    key={f.id}
                    className={`font-preset-item ${selectedFont === f.id ? 'is-selected' : ''}`}
                    onClick={() => handleSelectFont(f.id)}
                  >
                    <div className="font-item-info">
                      <span className={`font-item-name ${f.class}`}>{f.name}</span>
                      <span className="font-item-category">{f.category}</span>
                    </div>
                    <span className={`font-preview-sample ${f.class}`}>{f.sample}</span>
                  </div>
                ))}
              </div>
              <div className="popup-actions" style={{ marginTop: 12 }}>
                <button className="popup-btn-secondary" onClick={() => setShowFontModal(false)}>
                  Close
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ==============================================================
// 4. SECONDARY MANAGEMENT & SYNC HUB
// ==============================================================
function LibraryView() {
  const notes = useNotesList();
  const [tab, setTab] = useState<'all' | 'pinned' | 'checklists' | 'archived' | 'sync' | 'backup'>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [dockSide, setDockSide] = useState<'right' | 'left'>(() => {
    return (localStorage.getItem('sidenote_dock_side') as 'right' | 'left') || 'right';
  });
  const [defaultFont, setDefaultFont] = useState<string>(() => {
    return localStorage.getItem('sidenote_default_font') || 'roboto';
  });
  const [backupStatus, setBackupStatus] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Sync state
  const [deviceId, setDeviceId] = useState('');
  const [fingerprint, setFingerprint] = useState('');
  const [remoteAddress, setRemoteAddress] = useState('');
  const [sharedKey, setSharedKey] = useState('');
  const [syncStatus, setSyncStatus] = useState('');
  const [syncBusy, setSyncBusy] = useState(false);

  useEffect(() => {
    if (native) {
      invoke<string>('local_device_id')
        .then(setDeviceId)
        .catch(() => {});
      invoke<string>('local_device_public_key')
        .then(k => invoke<string>('public_key_fingerprint', { publicKey: k }))
        .then(setFingerprint)
        .catch(() => {});
    }
  }, []);

  const visibleNotes = useMemo(() => {
    return notes.filter(n => {
      const matchSearch = (n.title + ' ' + n.body).toLowerCase().includes(searchQuery.toLowerCase());
      if (!matchSearch) return false;
      if (tab === 'archived') return n.archived;
      if (n.archived) return false;
      if (tab === 'pinned') return n.pinned;
      if (tab === 'checklists') return n.isChecklist;
      return true;
    });
  }, [notes, tab, searchQuery]);

  const handleStartSync = async () => {
    if (!remoteAddress.trim() || sharedKey.length !== 64) return;
    setSyncBusy(true);
    try {
      const res = await invoke<{ applied: number; skipped: number; conflicts: number }>('lan_sync_peer', {
        address: remoteAddress.trim(),
        keyHex: sharedKey.trim(),
      });
      setSyncStatus(`Sync finished: ${res.applied} applied, ${res.skipped} up to date, ${res.conflicts} conflicts.`);
    } catch (e) {
      setSyncStatus('Sync error: ' + String(e));
    } finally {
      setSyncBusy(false);
    }
  };

  // Export all notes as a JSON file download
  const handleExportJSON = () => {
    try {
      const dataStr = 'data:text/json;charset=utf-8,' + encodeURIComponent(JSON.stringify(notes, null, 2));
      const downloadAnchor = document.createElement('a');
      const dateStamp = new Date().toISOString().split('T')[0];
      downloadAnchor.setAttribute('href', dataStr);
      downloadAnchor.setAttribute('download', `SideNote-Backup-${dateStamp}.json`);
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();
      setBackupStatus(`Successfully exported ${notes.length} notes!`);
    } catch (e) {
      setBackupStatus('Export failed: ' + String(e));
    }
  };

  // Import notes from a selected JSON backup file
  const handleImportJSON = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async event => {
      try {
        const content = event.target?.result as string;
        const parsed = JSON.parse(content);
        if (Array.isArray(parsed)) {
          let count = 0;
          for (const item of parsed) {
            if (item.id && (item.title !== undefined || item.body !== undefined)) {
              await saveNote({
                id: item.id || crypto.randomUUID(),
                title: item.title || '',
                body: item.body || '',
                color: typeof item.color === 'number' ? item.color : 0,
                isChecklist: Boolean(item.isChecklist),
                updatedAt: item.updatedAt || Date.now(),
                pinned: Boolean(item.pinned),
                archived: Boolean(item.archived),
                reminderAt: item.reminderAt || 0,
              });
              count++;
            }
          }
          setBackupStatus(`Successfully restored ${count} notes from backup!`);
        } else {
          setBackupStatus('Invalid backup file format.');
        }
      } catch (err) {
        setBackupStatus('Import failed: ' + String(err));
      }
    };
    reader.readAsText(file);
  };

  const handleToggleDockSide = (side: 'left' | 'right') => {
    setDockSide(side);
    localStorage.setItem('sidenote_dock_side', side);
    window.dispatchEvent(new Event('sidenote_dock_side_changed'));
  };

  return (
    <div className="library-window">
      {/* Header */}
      <header className="library-header">
        <div className="library-brand-block">
          <BrandBadge />
        </div>

        <nav className="library-tabs-nav">
          <button className={`library-tab-btn ${tab === 'all' ? 'is-active' : ''}`} onClick={() => setTab('all')}>
            <StickyNote size={14} /> All ({notes.filter(n => !n.archived).length})
          </button>
          <button className={`library-tab-btn ${tab === 'pinned' ? 'is-active' : ''}`} onClick={() => setTab('pinned')}>
            <Pin size={14} /> Pinned
          </button>
          <button className={`library-tab-btn ${tab === 'checklists' ? 'is-active' : ''}`} onClick={() => setTab('checklists')}>
            <CheckSquare size={14} /> Checklists
          </button>
          <button className={`library-tab-btn ${tab === 'archived' ? 'is-active' : ''}`} onClick={() => setTab('archived')}>
            <Archive size={14} /> Archive
          </button>
          <button className={`library-tab-btn ${tab === 'sync' ? 'is-active' : ''}`} onClick={() => setTab('sync')}>
            <Wifi size={14} /> Sync & Devices
          </button>
          <button className={`library-tab-btn ${tab === 'backup' ? 'is-active' : ''}`} onClick={() => setTab('backup')}>
            <Download size={14} /> Backup & Tools
          </button>
        </nav>

        <button
          className="primary-btn"
          onClick={async () => {
            const fresh = createFreshNote();
            await saveNote(fresh);
            await openFloatingNote(fresh);
          }}
        >
          <Plus size={16} /> New Note
        </button>
      </header>

      {/* Main Body */}
      <div className="library-content-body">
        {tab === 'backup' ? (
          <div className="sync-pane-card">
            <h2 style={{ margin: '0 0 8px 0', fontSize: 18, fontWeight: 800 }}>Backup, Preferences & Desktop Tools</h2>
            <p style={{ margin: '0 0 18px 0', fontSize: 13, color: 'var(--ink-secondary)', lineHeight: 1.6 }}>
              Manage full JSON backups, switch screen edge dock position, and test audio alerts.
            </p>

            {/* Edge Dock Position Setting */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Screen Edge Dock Alignment</h4>
                <p>Choose which side of your display the peeking dock rail anchors to.</p>
              </div>
              <div className="backup-actions">
                <button
                  className={dockSide === 'right' ? 'primary-btn' : 'outline-btn'}
                  onClick={() => handleToggleDockSide('right')}
                >
                  Right Edge
                </button>
                <button
                  className={dockSide === 'left' ? 'primary-btn' : 'outline-btn'}
                  onClick={() => handleToggleDockSide('left')}
                >
                  Left Edge
                </button>
              </div>
            </div>

            {/* Default Note Typography Card */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Default Note Typography</h4>
                <p>Choose the default font family for new sticky notes and floating cards.</p>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {NOTE_FONTS.map(f => (
                  <button
                    key={f.id}
                    className={defaultFont === f.id ? 'primary-btn' : 'outline-btn'}
                    style={{ fontSize: 12, padding: '4px 10px' }}
                    onClick={() => {
                      setDefaultFont(f.id);
                      localStorage.setItem('sidenote_default_font', f.id);
                      setBackupStatus(`Default typography set to ${f.name}`);
                      setTimeout(() => setBackupStatus(''), 3000);
                    }}
                  >
                    <span className={f.class}>{f.name}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* JSON Export Card */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Export Notes (JSON)</h4>
                <p>Download a complete readable JSON archive of all {notes.length} notes.</p>
              </div>
              <button className="outline-btn" onClick={handleExportJSON}>
                <Download size={15} /> Export Backup
              </button>
            </div>

            {/* JSON Import Card */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Restore Notes (JSON)</h4>
                <p>Import notes from a previously exported SideNote JSON file.</p>
              </div>
              <div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".json,application/json"
                  style={{ display: 'none' }}
                  onChange={handleImportJSON}
                />
                <button className="outline-btn" onClick={() => fileInputRef.current?.click()}>
                  <Upload size={15} /> Select File…
                </button>
              </div>
            </div>

            {/* Chime Test Card */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Audio Reminder Chime</h4>
                <p>Preview the pleasant high-fidelity notification sound.</p>
              </div>
              <button className="outline-btn" onClick={playReminderChime}>
                <Volume2 size={15} /> Test Chime
              </button>
            </div>

            {backupStatus && (
              <p style={{ marginTop: 14, fontSize: 13, fontWeight: 700, color: 'var(--brand-violet)' }}>
                {backupStatus}
              </p>
            )}
          </div>
        ) : tab !== 'sync' ? (
          <>
            <div className="library-search-bar">
              <Search size={18} color="var(--ink-secondary)" />
              <input
                placeholder="Search your notes and checklists..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
              />
            </div>

            <div className="library-notes-grid">
              {visibleNotes.map(n => {
                const colorMeta = NOTE_COLORS[n.color] || NOTE_COLORS[0];
                return (
                  <div
                    key={n.id}
                    className="library-note-card"
                    style={{ background: colorMeta.bg, color: colorMeta.ink, borderColor: colorMeta.border }}
                    onClick={() => void openFloatingNote(n)}
                  >
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                        <span style={{ fontSize: 9, fontWeight: 800, textTransform: 'uppercase', opacity: 0.65 }}>
                          {n.isChecklist ? 'Checklist' : 'Note'}
                        </span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                          {n.reminderAt > 0 && <Bell size={12} color="var(--brand-violet)" />}
                          {n.pinned && <Pin size={13} fill="currentColor" />}
                        </div>
                      </div>
                      <h3 style={{ margin: '0 0 6px 0', fontSize: 15, fontWeight: 800 }}>{n.title || 'Untitled Note'}</h3>
                      <p
                        style={{
                          margin: 0,
                          fontSize: 12,
                          lineHeight: 1.5,
                          opacity: 0.8,
                          display: '-webkit-box',
                          WebkitLineClamp: 3,
                          WebkitBoxOrient: 'vertical',
                          overflow: 'hidden',
                        }}
                      >
                        {n.body || 'Empty note...'}
                      </p>
                    </div>

                    <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 14, fontSize: 10, opacity: 0.6 }}>
                      <span>{new Date(n.updatedAt).toLocaleDateString()}</span>
                      <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                        Float on desktop <ChevronRight size={12} />
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        ) : (
          <div className="sync-pane-card">
            <h2 style={{ margin: '0 0 8px 0', fontSize: 18, fontWeight: 800 }}>Encrypted Cross-Platform Sync</h2>
            <p style={{ margin: '0 0 16px 0', fontSize: 13, color: 'var(--ink-secondary)', lineHeight: 1.6 }}>
              Sync notes seamlessly between Android and Windows over your local Wi-Fi network with zero cloud servers.
            </p>

            <div style={{ marginBottom: 16 }}>
              <strong style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: 0.8, color: 'var(--ink-secondary)' }}>
                Your Device Identity
              </strong>
              <div className="sync-device-badge">
                <ShieldCheck size={16} color="var(--brand-violet)" />
                <span>Fingerprint: {fingerprint || 'Generating...'}</span>
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
              <input
                style={{
                  padding: '10px 14px',
                  borderRadius: 12,
                  border: '1px solid var(--surface-border)',
                  background: 'var(--surface-field)',
                  fontSize: 13,
                }}
                placeholder="Target Device IP Address (e.g. 192.168.1.45)"
                value={remoteAddress}
                onChange={e => setRemoteAddress(e.target.value)}
              />
              <input
                style={{
                  padding: '10px 14px',
                  borderRadius: 12,
                  border: '1px solid var(--surface-border)',
                  background: 'var(--surface-field)',
                  fontSize: 13,
                }}
                placeholder="64-character 256-bit pairing secret"
                value={sharedKey}
                onChange={e => setSharedKey(e.target.value)}
              />
              <button
                className="primary-btn"
                disabled={syncBusy || !remoteAddress.trim() || sharedKey.length !== 64}
                onClick={handleStartSync}
                style={{ marginTop: 8 }}
              >
                <RefreshCw size={15} /> {syncBusy ? 'Syncing...' : 'Sync with Device'}
              </button>
              {syncStatus && (
                <p style={{ fontSize: 12, marginTop: 8, color: 'var(--brand-violet)' }}>{syncStatus}</p>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// ==============================================================
// 5. BROWSER PREVIEW SIMULATOR (FOR DEV & SCREENSHOT CAPTURE)
// ==============================================================
function BrowserDesktopSimulator() {
  const notes = useNotesList();
  const [activeFloatingNotes, setActiveFloatingNotes] = useState<Note[]>([
    defaultSampleNotes[0],
    defaultSampleNotes[1],
  ]);
  const [showLibraryModal, setShowLibraryModal] = useState(false);

  useEffect(() => {
    const handleFloat = (e: any) => {
      const n = e.detail as Note;
      setActiveFloatingNotes(prev => (prev.some(x => x.id === n.id) ? prev : [...prev, n]));
    };
    const handleCloseFloat = (e: any) => {
      const id = e.detail as string;
      setActiveFloatingNotes(prev => prev.filter(x => x.id !== id));
    };
    const handleOpenLib = () => setShowLibraryModal(true);

    window.addEventListener('sidenote_preview_float', handleFloat);
    window.addEventListener('sidenote_preview_close_float', handleCloseFloat);
    window.addEventListener('sidenote_preview_library', handleOpenLib);

    return () => {
      window.removeEventListener('sidenote_preview_float', handleFloat);
      window.removeEventListener('sidenote_preview_close_float', handleCloseFloat);
      window.removeEventListener('sidenote_preview_library', handleOpenLib);
    };
  }, []);

  return (
    <div
      style={{
        width: '100vw',
        height: '100vh',
        position: 'relative',
        background: 'radial-gradient(ellipse at bottom, #1B2735 0%, #090A0F 100%)',
        overflow: 'hidden',
      }}
    >
      {/* Subtle desktop wallpaper glow */}
      <div
        style={{
          position: 'absolute',
          top: '20%',
          left: '30%',
          width: '500px',
          height: '500px',
          background: 'radial-gradient(circle, rgba(138, 75, 255, 0.15) 0%, transparent 70%)',
          pointerEvents: 'none',
        }}
      />

      {/* Floating Sticky Notes on Desktop */}
      <div style={{ position: 'absolute', top: 50, left: 70, width: 340, height: 430 }}>
        {activeFloatingNotes.length > 0 && <FloatingNoteCard noteId={activeFloatingNotes[0].id} />}
      </div>

      {activeFloatingNotes.length > 1 && (
        <div style={{ position: 'absolute', top: 110, left: 450, width: 340, height: 430 }}>
          <FloatingNoteCard noteId={activeFloatingNotes[1].id} />
        </div>
      )}

      {/* Edge Dock on Right */}
      <div style={{ position: 'absolute', top: 0, right: 0, height: '100%' }}>
        <EdgeDock />
      </div>

      {/* Secondary Library Modal */}
      {showLibraryModal && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: 'rgba(0,0,0,0.5)',
            display: 'grid',
            placeItems: 'center',
            zIndex: 999,
          }}
          onClick={() => setShowLibraryModal(false)}
        >
          <div
            style={{ width: 880, height: 620, borderRadius: 20, overflow: 'hidden' }}
            onClick={e => e.stopPropagation()}
          >
            <LibraryView />
          </div>
        </div>
      )}
    </div>
  );
}

// ==============================================================
// ROOT ROUTER
// ==============================================================
const root = createRoot(document.getElementById('root')!);

if (!native && view === 'main') {
  root.render(<BrowserDesktopSimulator />);
} else if (view === 'note') {
  root.render(<FloatingNoteCard />);
} else if (view === 'library') {
  root.render(<LibraryView />);
} else {
  // view === 'main' or view === 'edge' in Tauri desktop
  root.render(<EdgeDock />);
}
