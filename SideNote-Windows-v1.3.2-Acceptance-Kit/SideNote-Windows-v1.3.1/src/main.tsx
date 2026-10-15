import React, { useEffect, useMemo, useState, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { invoke } from '@tauri-apps/api/core';
import { emit, listen } from '@tauri-apps/api/event';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import { getCurrentWindow, LogicalPosition, LogicalSize, availableMonitors, currentMonitor } from '@tauri-apps/api/window';
import {
  Plus, Search, Pin, Archive, Trash2, Settings, Minus, X,
  StickyNote, ChevronRight, Check, CheckSquare, Square, Maximize2, Minimize2,
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

// 6 Curated Pastel Note Colors matching Figma & SideNote Design System
export const NOTE_COLORS = [
  { name: 'Warm Coral', bg: '#FF8A8C', border: '#FFAFB1', ink: '#4A1E2B', darkBg: '#442B1A', darkInk: '#FFDFC7' }, // 0
  { name: 'Soft Yellow', bg: '#FEF4AA', border: '#F3E692', ink: '#3F3714', darkBg: '#3E381C', darkInk: '#FFF6B8' }, // 1
  { name: 'Sky Cyan', bg: '#90E4FF', border: '#B2D7F5', ink: '#1A364E', darkBg: '#1A2F45', darkInk: '#D6EBFC' },   // 2
  { name: 'Lavender Purple', bg: '#DBACFF', border: '#D1BDF5', ink: '#372352', darkBg: '#32254B', darkInk: '#EBE0FD' }, // 3
  { name: 'Mint Green', bg: '#D4F5DE', border: '#AEE0BD', ink: '#1B3D28', darkBg: '#1B3827', darkInk: '#D4F5DE' },  // 4
  { name: 'Blush Pink', bg: '#FFD4DE', border: '#F0B5C4', ink: '#4A1E2B', darkBg: '#42202D', darkInk: '#FFD4DE' },  // 5
];

const params = new URLSearchParams(window.location.search);
const view = params.get('view') || 'main';
const targetNoteId = params.get('id');
const native = Boolean((window as any).__TAURI_INTERNALS__);
const EVENT_CHANGE = 'sidenote_changed';
const syncChannel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('sidenote_realtime_sync') : null;

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
    title: '🚀 Project Launch',
    body: 'Ship v1.3.2 release with interactive grip drag and compact side widget.',
    color: 0, // Coral #FF8A8C
    isChecklist: false,
    updatedAt: Date.now() - 30000,
    pinned: true,
    archived: false,
    reminderAt: 0,
  },
  {
    id: 'sample-2',
    title: '✨ Daily Focus',
    body: 'Focus on high impact tasks, edge-dock convenience, and clean architecture.',
    color: 1, // Yellow #FEF4AA
    isChecklist: false,
    updatedAt: Date.now() - 60000,
    pinned: false,
    archived: false,
    reminderAt: 0,
  },
  {
    id: 'sample-3',
    title: '🛒 Shopping List',
    body: '[x] Fresh avocados\n[x] Matcha green tea\n[ ] Almond milk\n[ ] Dark chocolate',
    color: 2, // Cyan #90E4FF
    isChecklist: true,
    updatedAt: Date.now() - 90000,
    pinned: false,
    archived: false,
    reminderAt: 0,
  },
  {
    id: 'sample-4',
    title: '💡 Quick Ideas',
    body: 'Floating desktop notes with double click, instant autosave, and zero latency.',
    color: 3, // Purple #DBACFF
    isChecklist: false,
    updatedAt: Date.now() - 120000,
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
    try {
      await emit('sidenote_changed', n);
    } catch {}
  } else {
    const list = loadLocalDemo().filter(x => x.id !== n.id);
    localStorage.setItem(DEMO_KEY, JSON.stringify([n, ...list]));
  }
  syncChannel?.postMessage({ type: 'upsert', note: n });
  localStorage.setItem(EVENT_CHANGE, String(Date.now()));
  window.dispatchEvent(new Event(EVENT_CHANGE));
}

async function deleteNote(id: string) {
  if (native) {
    await invoke('delete_note', { id });
    try {
      await emit('sidenote_deleted', id);
    } catch {}
  } else {
    const list = loadLocalDemo().filter(x => x.id !== id);
    localStorage.setItem(DEMO_KEY, JSON.stringify(list));
  }
  syncChannel?.postMessage({ type: 'delete', id });
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
    if (typeof x === 'number' && typeof y === 'number') {
      try {
        await prior.setPosition(new LogicalPosition(x, y));
      } catch {}
    }
    await prior.show();
    await prior.setFocus();
    return true;
  }
  const isLib = label.startsWith('library');
  const w = new WebviewWindow(label, {
    url,
    title: 'SideNote',
    width,
    height,
    x,
    y,
    decorations: false,
    transparent: true,
    alwaysOnTop: !isLib,
    shadow: false,
    resizable: true,
    minimizable: true,
    maximizable: true,
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

async function openFloatingNote(n: Note, targetX?: number, targetY?: number) {
  if (native) {
    const label = placementKey(n.id);
    const old = await safePlacement(label);
    const p = old ? await clampPlacement(old) : null;
    let finalX = p?.x;
    let finalY = p?.y;

    const cardW = p?.width || 340;
    const cardH = p?.height || 430;

    const mon = await currentMonitor();
    const scale = mon?.scaleFactor || 1;

    if (typeof targetX === 'number' && typeof targetY === 'number') {
      const rawPlacement = {
        label,
        x: Math.round(targetX - cardW / 2),
        y: Math.round(targetY - 40),
        width: cardW,
        height: cardH,
      };
      const clamped = await clampPlacement(rawPlacement);
      finalX = clamped.x;
      finalY = clamped.y;
    } else if (typeof finalX !== 'number' || typeof finalY !== 'number') {
      if (mon) {
        const screenW = mon.size.width / scale;
        const screenH = mon.size.height / scale;
        finalX = (mon.position.x / scale) + Math.max(40, screenW - cardW - 320);
        finalY = (mon.position.y / scale) + Math.max(40, Math.round((screenH - cardH) / 2));
      }
    }

    await spawnWindow(label, '/?view=note&id=' + encodeURIComponent(n.id), cardW, cardH, finalX, finalY);
  } else {
    // In web preview, fire custom event to open floating card
    window.dispatchEvent(new CustomEvent('sidenote_preview_float', { detail: { note: n, x: targetX, y: targetY } }));
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

// Hook to observe notes with instant real-time cross-window sync
function useNotesList() {
  const [notes, setNotes] = useState<Note[]>([]);
  useEffect(() => {
    let active = true;
    const reload = () =>
      loadNotes().then(items => {
        if (active) setNotes(items);
      });
    reload();
    const timer = setInterval(reload, 600);

    let unlistenChange: (() => void) | null = null;
    let unlistenDelete: (() => void) | null = null;
    if (native) {
      listen<Note>('sidenote_changed', (evt) => {
        if (!active) return;
        const updated = evt.payload;
        setNotes(prev => {
          const idx = prev.findIndex(x => x.id === updated.id);
          if (idx >= 0) {
            const next = [...prev];
            next[idx] = updated;
            return next;
          }
          return [updated, ...prev];
        });
        reload();
      }).then(fn => {
        if (active) unlistenChange = fn;
        else fn();
      }).catch(console.warn);

      listen<string>('sidenote_deleted', () => {
        if (!active) return;
        reload();
      }).then(fn => {
        if (active) unlistenDelete = fn;
        else fn();
      }).catch(console.warn);
    }

    const handleStorage = (e: StorageEvent) => {
      if (e.key === EVENT_CHANGE || e.key === DEMO_KEY) {
        reload();
      }
    };
    window.addEventListener('storage', handleStorage);
    window.addEventListener(EVENT_CHANGE, reload);

    const handleChannelMsg = (evt: MessageEvent) => {
      if (!active) return;
      if (evt.data?.type === 'upsert' && evt.data.note) {
        const updated = evt.data.note as Note;
        setNotes(prev => {
          const idx = prev.findIndex(x => x.id === updated.id);
          if (idx >= 0) {
            const next = [...prev];
            next[idx] = updated;
            return next;
          }
          return [updated, ...prev];
        });
      } else if (evt.data?.type === 'delete' && evt.data.id) {
        setNotes(prev => prev.filter(x => x.id !== evt.data.id));
      }
      reload();
    };
    if (syncChannel) {
      syncChannel.addEventListener('message', handleChannelMsg);
    }

    return () => {
      active = false;
      clearInterval(timer);
      unlistenChange?.();
      unlistenDelete?.();
      window.removeEventListener('storage', handleStorage);
      window.removeEventListener(EVENT_CHANGE, reload);
      if (syncChannel) {
        syncChannel.removeEventListener('message', handleChannelMsg);
      }
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
// 2. PRIMARY EXPERIENCE: FIGMA EDGE DOCK & PEEKING TABS
// ==============================================================

const FIGMA_COLORS = NOTE_COLORS.map(c => c.bg);
const RED_PATH = 'M530.855 156.628C531.03 146.293 539.476 138 549.813 138H785.895C796.388 138 804.899 146.512 804.803 157.005C804.362 205.106 802.629 247.648 805.936 316.87C806.457 327.781 797.795 337 786.87 337H546.75C535.968 337 527.351 328.027 527.74 317.252C530.714 234.74 529.879 214.236 530.855 156.628Z';
const YEL_PATH = 'M530.79 371.547C530.991 359.036 541.216 349 553.729 349H781.895C794.597 349 804.898 359.3 804.763 372.001C804.28 417.514 802.785 458.801 805.739 523.663C806.341 536.873 795.857 548 782.633 548H550.908C537.857 548 527.428 537.091 527.89 524.047C530.62 446.874 529.93 425.249 530.79 371.547Z';

function FigmaSvgDefs() {
  return (
    <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true">
      <defs>
        <filter id="b9" x="-30%" y="-100%" width="160%" height="300%"><feGaussianBlur stdDeviation="9" /></filter>
        <filter id="b35" x="-30%" y="-100%" width="160%" height="300%"><feGaussianBlur stdDeviation="3.5" /></filter>
        <filter id="b4" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="4" /></filter>
        <linearGradient id="g0" x1="609.661" y1="323.222" x2="543.838" y2="346.184" gradientUnits="userSpaceOnUse">
          <stop /><stop offset="1" stopColor="#202020" stopOpacity=".36" />
        </linearGradient>
        <linearGradient id="g1" x1="628.03" y1="142.592" x2="631.857" y2="356.899" gradientUnits="userSpaceOnUse">
          <stop stopOpacity="0" /><stop offset="1" />
        </linearGradient>
        <linearGradient id="rg2" x1="596.474" y1="74.4731" x2="634.134" y2="245.793" gradientUnits="userSpaceOnUse">
          <stop offset=".247555" stopColor="#FFAFB1" stopOpacity=".63" />
          <stop offset=".459759" stopColor="#C6686A" stopOpacity=".17" />
          <stop offset=".629337" stopColor="#FF8A8C" stopOpacity=".22" />
          <stop offset=".78707" stopColor="#FF8A8C" />
        </linearGradient>
        <radialGradient id="rg3" cx="0" cy="0" r="1" gradientTransform="matrix(-250.526 -78.8346 190.901 -320.739 879.631 371.442)" gradientUnits="userSpaceOnUse">
          <stop stopColor="#FFA8A9" /><stop offset="1" stopColor="#FF8A8C" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="rg4" cx="798.6" cy="146.4" r="90" gradientUnits="userSpaceOnUse">
          <stop offset=".577" stopColor="#DB7C7E" stopOpacity=".34" /><stop offset="1" stopColor="#FF8A8C" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="rg5" cx="729" cy="124" r="100" gradientUnits="userSpaceOnUse">
          <stop offset=".577" stopColor="#DB7C7E" stopOpacity=".34" /><stop offset="1" stopColor="#FF8A8C" stopOpacity="0" />
        </radialGradient>
      </defs>
    </svg>
  );
}

// ============================================================
// Markdown → rich HTML renderer (inline spans, headings, links)
// ============================================================
function renderMarkdown(text: string): string {
  if (!text) return '';
  const lines = text.split('\n');
  const out: string[] = [];
  for (const raw of lines) {
    let line = raw
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    // Headings
    if (line.startsWith('### ')) {
      out.push(`<span style="font-size:1.22em;font-weight:800;display:block;margin:3px 0">${line.slice(4)}</span>`);
      continue;
    }
    if (line.startsWith('## ')) {
      out.push(`<span style="font-size:1.32em;font-weight:800;display:block;margin:4px 0">${line.slice(3)}</span>`);
      continue;
    }
    if (line.startsWith('# ')) {
      out.push(`<span style="font-size:1.45em;font-weight:900;display:block;margin:5px 0">${line.slice(2)}</span>`);
      continue;
    }
    // Bullet
    if (line.startsWith('• ') || line.startsWith('- ') || line.startsWith('* ')) {
      line = '&bull;&nbsp;' + line.slice(2);
    }
    // Checkbox items in text
    if (line.startsWith('[ ] ')) {
      line = '&#9634;&nbsp;' + line.slice(4);
    } else if (line.startsWith('[x] ') || line.startsWith('[X] ')) {
      line = '&#9632;&nbsp;<del>' + line.slice(4) + '</del>';
    }
    // Bold **text** and __text__
    line = line.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    line = line.replace(/__(.+?)__/g, '<strong>$1</strong>');
    // Italic *text* and _text_
    line = line.replace(/\*([^*\n]+)\*/g, '<em>$1</em>');
    line = line.replace(/_([^_<>\n]+)_/g, '<em>$1</em>');
    // Underline <u>text</u>
    line = line.replace(/&lt;u&gt;(.+?)&lt;\/u&gt;/gi, '<u style="text-decoration:underline">$1</u>');
    // Link [title](url)
    line = line.replace(/\[([^\]]+)\]\(([^)]+)\)/g,
      (_, title, href) => `<a href="${href}" target="_blank" rel="noopener noreferrer" class="note-md-link" style="color:var(--brand-violet);text-decoration:underline;cursor:pointer;font-weight:600">${title}</a>`);
    out.push(`<span style="display:block;min-height:1.25em">${line || '&nbsp;'}</span>`);
  }
  return out.join('');
}

// Global delegated link opener (opens clicked links in default system browser)
if (typeof document !== 'undefined') {
  document.addEventListener('click', (e) => {
    const a = (e.target as HTMLElement).closest('a');
    if (a) {
      const href = a.getAttribute('href');
      if (href && (href.startsWith('http://') || href.startsWith('https://') || href.startsWith('mailto:'))) {
        e.preventDefault();
        e.stopPropagation();
        if (native) {
          try {
            invoke('plugin:opener|open_url', { path: href }).catch(() => {
              window.open(href, '_blank', 'noopener,noreferrer');
            });
          } catch {
            window.open(href, '_blank', 'noopener,noreferrer');
          }
        } else {
          window.open(href, '_blank', 'noopener,noreferrer');
        }
      }
    }
  });
}

function SvgShadow() {
  return (
    <g transform="translate(-530,-138)">
      <path filter="url(#b9)" d="M709.161 306.384H560.677L554.554 349.245L710.692 339.877L709.161 306.384Z" fill="#000" fillOpacity=".29" />
      <path filter="url(#b35)" d="M712.988 317.865H542.308L539.246 346.184L727.53 336.234L712.988 317.865Z" fill="url(#g0)" fillOpacity=".4" />
      <path filter="url(#b4)" d="M713.753 170.911L536.185 165.554L533.123 330.876H724.469L713.753 170.911Z" fill="url(#g1)" fillOpacity=".3" />
    </g>
  );
}

function SvgShape({ colorIndex }: { colorIndex: number }) {
  const c = Math.abs(colorIndex) % NOTE_COLORS.length;
  const col = NOTE_COLORS[c].bg;
  if (c === 0) {
    return (
      <g transform="translate(-530,-138)">
        <path d={RED_PATH} fill={col} />
        {['rg2', 'rg3', 'rg4', 'rg5'].map(g => (
          <path key={g} d={RED_PATH} fill={`url(#${g})`} />
        ))}
      </g>
    );
  }
  return <path transform="translate(-530,-349)" d={YEL_PATH} fill={col} />;
}

function EdgeDock() {
  const notes = useNotesList();
  const [openNoteId, setOpenNoteId] = useState<string | null>(null);
  const [toastInfo, setToastInfo] = useState<{ text: string; undoNote?: Note } | null>(null);
  const toastTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const frameRef = useRef<HTMLDivElement>(null);
  // Width factor: 0.6 = narrow, 1.0 = wide (affects card + OS window)
  const [widthFactor, setWidthFactor] = useState<number>(() => {
    return parseFloat(localStorage.getItem('sidenote_width_factor') || '0.85');
  });

  // Anti-glitch state refs & mutex
  const isExpandedRef = useRef(false);
  const isBusyRef = useRef(false);
  const collapseTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Drag reordering state
  const [customOrder, setCustomOrder] = useState<string[]>(() => {
    try {
      const raw = localStorage.getItem('sidenote_dock_order');
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  });
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dragTop, setDragTop] = useState<number | null>(null);
  const [dragLeft, setDragLeft] = useState<number | null>(null);
  const [isTearOff, setIsTearOff] = useState(false);

  // Stable scale strictly based on monitor screen display height (never distorted by window resizing or full-screen drag canvas)
  useEffect(() => {
    const applyStableScale = async () => {
      let screenH = 1080;
      if (native) {
        try {
          const mon = await currentMonitor();
          if (mon) {
            screenH = mon.size.height / (mon.scaleFactor || 1);
          }
        } catch {}
      } else {
        screenH = window.innerHeight;
      }
      const targetHeight = Math.max(420, Math.round(screenH * 0.58));
      const s = Math.min(0.46, Math.max(0.36, (targetHeight - 24) / 1340));
      document.documentElement.style.setProperty('--s', String(s));
      if (frameRef.current) {
        const topOffset = Math.max(4, Math.round((screenH * 0.58 - 1340 * s) / 2));
        frameRef.current.style.top = `${topOffset}px`;
      }
    };
    void applyStableScale();
    void adjustDockWidth(false);
  }, [widthFactor]);

  // Background reminder watcher: monitors notes and triggers pleasant chime
  useEffect(() => {
    const checkReminders = async () => {
      const now = Date.now();
      for (const n of notes) {
        if (n.reminderAt > 0 && n.reminderAt <= now && !n.archived) {
          playReminderChime();
          showToast(`⏰ Reminder: ${n.title || 'Note reminder'}`);
          await saveNote({ ...n, reminderAt: 0, updatedAt: Date.now() });
          await openFloatingNote(n);
        }
      }
    };
    const interval = setInterval(checkReminders, 8000);
    return () => clearInterval(interval);
  }, [notes]);

  // Smooth OS window sizing with zero tear & zero flicker (Atomic Win32 SetWindowPos)
  const adjustDockWidth = async (isOpen: boolean) => {
    if (!native) return;
    try {
      const mon = await currentMonitor();
      if (!mon) return;
      const scale = mon.scaleFactor || 1;
      const screenWidth = mon.size.width / scale;
      const screenHeight = mon.size.height / scale;

      // Safe dock height matching 58% display height
      const targetHeight = Math.max(420, Math.round(screenHeight * 0.58));
      const s = Math.min(0.46, Math.max(0.36, (targetHeight - 24) / 1340));
      const minOpenW = Math.round(580 * s);
      const openWidth = Math.max(minOpenW, Math.round(760 * s * widthFactor));
      // When collapsed, width strictly matches peeking tabs (199 * s ~ 85px) so desktop behind is 100% clickable
      const collapsedWidth = Math.round(202 * s);
      const targetWidth = isOpen ? Math.max(openWidth, collapsedWidth) : collapsedWidth;

      // Win32 SetWindowPos expects physical device pixels: convert logical to physical
      const physW = Math.round(targetWidth * scale);
      const physH = Math.round(targetHeight * scale);
      const physX = mon.position.x + mon.size.width - physW;
      const physY = mon.position.y + Math.round((mon.size.height - physH) / 2);

      await invoke('set_window_bounds_atomic', {
        x: physX,
        y: physY,
        width: physW,
        height: physH,
      });
    } catch (e) {
      console.warn('Dock move error', e);
    }
  };

  const expandDock = async (noteIdToOpen?: string) => {
    if (collapseTimerRef.current) {
      clearTimeout(collapseTimerRef.current);
      collapseTimerRef.current = null;
    }
    if (noteIdToOpen) {
      setOpenNoteId(noteIdToOpen);
    }
    if (isExpandedRef.current || isBusyRef.current) return;
    isBusyRef.current = true;
    try {
      await adjustDockWidth(true);
      isExpandedRef.current = true;
    } finally {
      isBusyRef.current = false;
    }
  };

  const collapseDock = () => {
    if (draggingIdRef.current) return; // Don't collapse while dragging
    if (collapseTimerRef.current) clearTimeout(collapseTimerRef.current);
    collapseTimerRef.current = setTimeout(() => {
      if (draggingIdRef.current) return;
      // Don't collapse if user is actively typing in text editor
      if (document.activeElement && document.activeElement.classList.contains('txt')) {
        return;
      }
      
      // 1. Fold card back into tab
      setOpenNoteId(null);

      // 2. Shrink window back to narrow 85px width so desktop behind is completely unblocked
      setTimeout(async () => {
        if (draggingIdRef.current) return;
        isBusyRef.current = true;
        try {
          await adjustDockWidth(false);
          isExpandedRef.current = false;
        } finally {
          isBusyRef.current = false;
        }
      }, 160);
    }, 100);
  };

  // Window-level tracking: immediately collapse dock when pointer leaves window area
  useEffect(() => {
    const onWinLeave = () => {
      if (!draggingIdRef.current) {
        collapseDock();
      }
    };
    window.addEventListener('pointerleave', onWinLeave);
    window.addEventListener('blur', onWinLeave);
    return () => {
      window.removeEventListener('pointerleave', onWinLeave);
      window.removeEventListener('blur', onWinLeave);
    };
  }, []);

  const showToast = (text: string, undoNote?: Note) => {
    if (toastTimeoutRef.current) clearTimeout(toastTimeoutRef.current);
    setToastInfo({ text, undoNote });
    toastTimeoutRef.current = setTimeout(() => {
      setToastInfo(null);
    }, undoNote ? 6000 : 2500);
  };

  // 4 recent notes displayed in the peeking tabs with custom drag order support
  const dockNotes = useMemo(() => {
    const unarchived = notes.filter(n => !n.archived);
    if (customOrder.length > 0) {
      return unarchived
        .sort((a, b) => {
          const idxA = customOrder.indexOf(a.id);
          const idxB = customOrder.indexOf(b.id);
          if (idxA !== -1 && idxB !== -1) return idxA - idxB;
          if (idxA !== -1) return -1;
          if (idxB !== -1) return 1;
          return Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt;
        })
        .slice(0, 4);
    }
    return unarchived
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
      .slice(0, 4);
  }, [notes, customOrder]);

  const TOP0 = 138;
  const PITCH = (769 - 138) / 3;
  const slot = (i: number) => TOP0 + i * PITCH;

  const draggingIdRef = useRef<string | null>(null);

  // Real-time Up/Down Drag Reordering on Grip & Free Full-Desktop Drag & Drop
  const handleGripPointerDown = async (e: React.PointerEvent, n: Note) => {
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();

    const targetEl = e.currentTarget as HTMLElement;
    try {
      targetEl.setPointerCapture(e.pointerId);
    } catch {}

    const currentIdx = dockNotes.findIndex(x => x.id === n.id);
    if (currentIdx === -1) return;

    setDraggingId(n.id);
    draggingIdRef.current = n.id;
    const baseTop = slot(currentIdx);
    setDragTop(baseTop);
    setDragLeft(0);

    const s = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--s')) || 0.40;
    const startScreenX = e.screenX;
    const startScreenY = e.screenY;
    const maxTop = slot(3) + 40;
    let lastIdx = currentIdx;
    let currentList = [...dockNotes];
    let hasMoved = false;

    document.body.style.userSelect = 'none';

    // Temporarily expand dock window to cover the screen so dragging has 100% full-screen canvas with zero cropping
    let restoreTopOffset = 9;
    if (native) {
      try {
        const mon = await currentMonitor();
        if (mon) {
          const scale = mon.scaleFactor || 1;
          const screenHeight = mon.size.height / scale;
          const targetHeight = Math.max(420, Math.round(screenHeight * 0.58));
          const targetY = Math.round((screenHeight - targetHeight) / 2);
          const topOffset = Math.max(4, Math.round((screenHeight * 0.58 - 1340 * s) / 2));
          restoreTopOffset = topOffset;

          // Compensate frame top so dock remains 100% stationary on monitor when window expands to y=0
          if (frameRef.current) {
            frameRef.current.style.top = `${targetY + topOffset}px`;
          }

          await invoke('set_window_bounds_atomic', {
            x: mon.position.x,
            y: mon.position.y,
            width: mon.size.width,
            height: mon.size.height,
          });
        }
      } catch (err) {
        console.warn('Canvas expansion error', err);
      }
    }

    let finished = false;

    const cleanup = () => {
      try {
        targetEl.releasePointerCapture(e.pointerId);
      } catch {}
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onCancel);
      document.body.style.userSelect = '';
      if (frameRef.current) {
        frameRef.current.style.top = `${restoreTopOffset}px`;
      }
    };

    const onPointerMove = (ev: PointerEvent) => {
      if (finished) return;
      const dist = Math.hypot(ev.screenX - startScreenX, ev.screenY - startScreenY);
      if (dist > 6) hasMoved = true;

      const deltaX = (ev.screenX - startScreenX) / s;
      const deltaY = (ev.screenY - startScreenY) / s;

      // Card follows cursor anywhere across the entire monitor!
      setDragLeft(deltaX);
      const newTop = baseTop + deltaY;
      setDragTop(newTop);

      // If user is near the edge dock, reorder slots vertically
      if (deltaX > -60) {
        const targetIdx = Math.max(0, Math.min(dockNotes.length - 1, Math.round((newTop - TOP0) / PITCH)));
        if (targetIdx !== lastIdx) {
          const item = currentList[lastIdx];
          currentList.splice(lastIdx, 1);
          currentList.splice(targetIdx, 0, item);
          lastIdx = targetIdx;
          const newOrder = currentList.map(x => x.id);
          setCustomOrder(newOrder);
          localStorage.setItem('sidenote_dock_order', JSON.stringify(newOrder));
        }
      }
    };

    const onPointerUp = async (ev: PointerEvent) => {
      if (finished) return;
      finished = true;
      cleanup();

      const deltaX = (ev.screenX - startScreenX) / s;

      // If user simply clicked without moving mouse, keep card open
      if (!hasMoved) {
        setDraggingId(null);
        draggingIdRef.current = null;
        setDragTop(null);
        setDragLeft(null);
        if (native) {
          await adjustDockWidth(true);
        }
        return;
      }

      // If user pulled note out into the desktop and released (dropped) it:
      if (deltaX < -50) {
        const dropX = ev.screenX;
        const dropY = ev.screenY;

        setDraggingId(null);
        draggingIdRef.current = null;
        setDragTop(null);
        setDragLeft(null);
        setOpenNoteId(null);

        // Shrink dock back to edge
        if (native) {
          await adjustDockWidth(false);
          isExpandedRef.current = false;
        }

        showToast('✨ Note placed on desktop');
        await openFloatingNote(n, dropX, dropY);
      } else {
        // User dropped note back inside the dock
        setDraggingId(null);
        draggingIdRef.current = null;
        setDragTop(null);
        setDragLeft(null);

        isExpandedRef.current = false;
        if (native) {
          await adjustDockWidth(false);
        }
      }
    };

    const onCancel = async () => {
      if (finished) return;
      finished = true;
      cleanup();
      setDraggingId(null);
      draggingIdRef.current = null;
      setDragTop(null);
      setDragLeft(null);
      isExpandedRef.current = false;
      if (native) {
        await adjustDockWidth(false);
      }
    };

    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);
    window.addEventListener('pointercancel', onCancel);
  };

  const handleCreateNew = async () => {
    if (dockNotes.length >= 4) {
      showToast('Side panel holds 4 notes — delete one first');
      return;
    }
    const fresh = createFreshNote();
    fresh.color = dockNotes.length % NOTE_COLORS.length;
    await saveNote(fresh);
    await expandDock(fresh.id);
  };

  const handleDelete = async (n: Note) => {
    setOpenNoteId(null);
    await deleteNote(n.id);
    showToast('Note deleted', n);
    collapseDock();
  };

  const handleTextChange = (n: Note, newText: string) => {
    const updated: Note = {
      ...n,
      body: newText,
      updatedAt: Date.now(),
    };
    void saveNote(updated);
  };

  return (
    <>
      <FigmaSvgDefs />
      <div
        className="frame"
        ref={frameRef}
        id="frame"
        onPointerEnter={() => {
          if (collapseTimerRef.current) clearTimeout(collapseTimerRef.current);
        }}
        onPointerLeave={collapseDock}
      >
        <div className="dock" />

        <div id="tabs" className={draggingId ? 'is-dragging' : ''}>
          {dockNotes.map((n, idx) => {
            const isOpen = openNoteId === n.id;
            const isDragging = draggingId === n.id;
            const noteColorIdx = typeof n.color === 'number' ? Math.abs(n.color) % NOTE_COLORS.length : (idx % NOTE_COLORS.length);
            const col = NOTE_COLORS[noteColorIdx].bg;

            return (
              <div
                key={n.id}
                className={`tab ${isOpen ? 'open' : ''} ${isDragging ? 'dragging' : ''} ${isDragging && isTearOff ? 'tearing-off' : ''}`}
                style={{
                  top: isDragging && dragTop !== null ? `${dragTop}px` : `${slot(idx)}px`,
                  left: isDragging && dragLeft !== null ? `${530 + dragLeft}px` : '530px',
                  ['--d' as any]: `${350 + idx * 90}ms`,
                  zIndex: isDragging ? 100 : (isOpen ? 20 : 1),
                }}
                onPointerDown={e => {
                  const target = e.target as HTMLElement;
                  if (!target.closest('button, .card-action-btn, .txt')) {
                    handleGripPointerDown(e, n);
                  }
                }}
                onPointerEnter={() => {
                  if (!draggingIdRef.current) void expandDock(n.id);
                }}
                onClick={() => {
                  if (!draggingIdRef.current) void expandDock(n.id);
                }}
              >
                <svg width="199" height="199">
                  <SvgShadow />
                  <SvgShape colorIndex={noteColorIdx} />
                </svg>

                {/* Note written text visible on peeking tabs */}
                <div className="tab-preview">
                  <div className="tab-preview-title">
                    {(n.title.trim() || (n.body ? n.body.split('\n')[0].replace(/^\[[ x]\]\s*/i, '').slice(0, 24) : 'Note')).replace(/[*_#`~]/g, '')}
                  </div>
                  {n.body && (
                    <div className="tab-preview-body">
                      {(n.title.trim() ? n.body : n.body.split('\n').slice(1).join(' ')).replace(/\n/g, ' ').replace(/[*_#`~]/g, '').replace(/\[[ x]\]/gi, '• ')}
                    </div>
                  )}
                </div>

                <div className="card" style={{ ['--c' as any]: col }}>
                  <div className="in">
                    <div
                      className="card-top-bar"
                      onPointerDown={e => {
                        const target = e.target as HTMLElement;
                        if (!target.closest('button, .card-action-btn')) {
                          handleGripPointerDown(e, n);
                        }
                      }}
                    >
                      <div
                        className="grip"
                        title="Drag to reorder or pull out onto desktop to float"
                        onPointerDown={e => handleGripPointerDown(e, n)}
                        onDoubleClick={async e => {
                          e.stopPropagation();
                          await openFloatingNote(n);
                        }}
                      >
                        <i /><i /><i />
                      </div>

                      <div className="card-actions">
                        <button
                          type="button"
                          className="card-action-btn"
                          title="Float note on desktop"
                          onClick={async e => {
                            e.stopPropagation();
                            await openFloatingNote(n);
                          }}
                        >
                          <Maximize2 size={20} />
                        </button>

                        <button
                          type="button"
                          className="card-action-btn del"
                          title="Delete note"
                          onClick={e => {
                            e.stopPropagation();
                            void handleDelete(n);
                          }}
                        >
                          <Trash2 size={20} />
                        </button>
                      </div>
                    </div>

                    <div
                      className="txt"
                      contentEditable
                      suppressContentEditableWarning
                      onInput={e => handleTextChange(n, (e.target as HTMLElement).innerText)}
                      onKeyDown={e => {
                        if (e.key === 'Escape') {
                          (e.target as HTMLElement).blur();
                          collapseDock();
                        }
                      }}
                    >
                      {n.body || n.title}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* Big Purple FAB with Rotating Plus */}
        <button className="fab" onClick={handleCreateNew} aria-label="New note" title="Create new note">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </button>

        {/* Rounded Folder Button to Open Full Library View */}
        <button className="folder" onClick={openLibraryWindow} aria-label="Folders" title="All notes, checklists & sync">
          <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round">
            <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
          </svg>
        </button>
      </div>

      {/* Pill Toast Notification with Undo */}
      {toastInfo && (
        <div className={`toast ${toastInfo ? 'on' : ''}`}>
          <span>{toastInfo.text}</span>
          {toastInfo.undoNote && (
            <button
              type="button"
              onClick={async () => {
                if (toastInfo.undoNote) {
                  await saveNote(toastInfo.undoNote);
                  setToastInfo(null);
                }
              }}
            >
              Undo
            </button>
          )}
        </div>
      )}
    </>
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
  const [showVoiceModal, setShowVoiceModal] = useState(false);
  const [voiceInputDraft, setVoiceInputDraft] = useState('');
  const [selectedFont, setSelectedFont] = useState<string>(() => {
    return localStorage.getItem('sidenote_font_' + (effectiveId || '')) || localStorage.getItem('sidenote_default_font') || 'roboto';
  });
  const [linkTitleInput, setLinkTitleInput] = useState('');
  const [linkUrlInput, setLinkUrlInput] = useState('https://');
  const [customReminderInput, setCustomReminderInput] = useState('');
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [saveStatus, setSaveStatus] = useState<'Saved' | 'Saving\u2026'>('Saved');
  const [isMaximized, setIsMaximized] = useState(false);
  // Preview / Edit toggle — shows rendered markdown when not actively editing
  const [isEditingBody, setIsEditingBody] = useState(false);

  useEffect(() => {
    if (native) {
      getCurrentWindow().isMaximized().then(setIsMaximized).catch(() => {});
    }
  }, []);

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
    if (note?.id) {
      localStorage.setItem('sidenote_font_' + note.id, fontId);
    }
    localStorage.setItem('sidenote_default_font', fontId);
    setShowFontModal(false);
    const found = NOTE_FONTS.find(f => f.id === fontId);
    showToast(`Font: ${found?.name || fontId}`);
  };

  useEffect(() => {
    if (note) {
      setTitle(note.title);
      setBody(note.body);
      setIsChecklist(note.isChecklist);
      setPinned(note.pinned);
      setColor(note.color);
      setReminderAt(note.reminderAt || 0);

      const storedFont = localStorage.getItem('sidenote_font_' + (note.id || effectiveId || '')) || localStorage.getItem('sidenote_default_font') || 'roboto';
      setSelectedFont(storedFont);

      // Initialize history stack on first note load
      if (history.length === 0) {
        setHistory([note.body]);
        setHistoryIdx(0);
      }
    }
  }, [note?.id, note?.color, note?.title, note?.body, note?.updatedAt]);

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
      try {
        await getCurrentWindow().minimize();
      } catch (e) {
        console.warn('Minimize error:', e);
      }
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }));
    }
  };

  const handleToggleMaximize = async () => {
    if (native) {
      try {
        const w = getCurrentWindow();
        const max = await w.isMaximized();
        if (max) {
          await w.unmaximize();
          setIsMaximized(false);
        } else {
          await w.maximize();
          setIsMaximized(true);
        }
      } catch (e) {
        console.warn('Maximize error:', e);
        try {
          await (getCurrentWindow() as any).toggleMaximize();
        } catch {}
        setIsMaximized(prev => !prev);
      }
    } else {
      setIsMaximized(prev => !prev);
    }
  };

  const handleCloseNote = async () => {
    if (native) {
      await persistPlacement(placementKey(note.id));
      await getCurrentWindow().close();
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }));
    }
  };

  const handleArchive = async () => {
    await updateFields({ archived: true });
    await handleCloseNote();
    showToast('Note archived');
  };

  // Text formatting insertion helper
  const insertFormatting = (prefix: string, suffix: string = '') => {
    // Switch to edit mode first so textarea is mounted
    setIsEditingBody(true);
    // Use setTimeout so textarea mounts before we try to access it
    setTimeout(() => {
      const el = textareaRef.current;
      if (!el) {
        const next = body + prefix + suffix;
        setBody(next);
        pushHistory(next);
        void updateFields({ body: next });
        return;
      }
      const start = el.selectionStart ?? body.length;
      const end = el.selectionEnd ?? body.length;
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
    }, 15);
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
    if (textareaRef.current) {
      textareaRef.current.focus();
    }
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      setShowVoiceModal(true);
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
      if (!target.closest('button, input, textarea, a, .color-swatch-dot, .preset-btn, .checklist-checkbox, .font-preset-item, .sticky-actions, .note-popup-overlay, .note-popup-card, [data-no-drag]')) {
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
    <div className="floating-card-wrapper">
      {toastMessage && (
        <div className="desktop-toast-banner" onClick={() => setToastMessage(null)}>
          <Sparkles size={14} color="#FFF" />
          <span>{toastMessage}</span>
        </div>
      )}

      <div
        className={`floating-sticky-note ${isMaximized ? 'is-maximized' : ''}`}
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

            {/* Archive Note */}
            <button
              className="sticky-icon-btn"
              title="Archive note"
              onClick={handleArchive}
            >
              <Archive size={13} />
            </button>

            {/* Minimize Window */}
            <button
              className="sticky-icon-btn"
              title="Minimize window"
              onClick={handleMinimize}
            >
              <Minus size={13} />
            </button>

            {/* Full Size / Maximize */}
            <button
              className="sticky-icon-btn"
              title={isMaximized ? "Restore size" : "Full size / Maximize"}
              onClick={handleToggleMaximize}
            >
              {isMaximized ? <Minimize2 size={12} /> : <Maximize2 size={12} />}
            </button>

            {/* Close Note Window */}
            <button
              className="sticky-icon-btn is-close"
              title="Close note window"
              onClick={handleCloseNote}
            >
              <X size={13} />
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

          {/* Preview / Edit mode toggle */}
          <button
            className="control-mode-pill"
            style={{
              marginLeft: 'auto',
              background: !isEditingBody ? 'var(--brand-violet)' : 'rgba(0,0,0,0.06)',
              color: !isEditingBody ? '#fff' : 'currentColor',
              fontWeight: 700,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4
            }}
            title={isEditingBody ? 'Switch to rendered markdown preview' : 'Switch to text editing mode'}
            onClick={() => setIsEditingBody(prev => !prev)}
          >
            <span>{!isEditingBody ? '👁️ Rich View' : '✏️ Edit Mode'}</span>
          </button>
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
            isEditingBody ? (
              <textarea
                ref={textareaRef}
                className={`sticky-textarea ${fontClass}`}
                placeholder={isChecklist ? '[ ] First task…\n[ ] Second task…' : 'Jot down a quick thought…'}
                value={body}
                autoFocus
                onChange={e => setBody(e.target.value)}
                onBlur={() => {
                  handleBodyBlur();
                  setIsEditingBody(false);
                }}
                onKeyDown={handleTextareaKeyDown}
              />
            ) : (
              <div
                className={`sticky-textarea sticky-preview ${fontClass}`}
                title="Click to edit"
                onClick={() => setIsEditingBody(true)}
                style={{ cursor: 'text', whiteSpace: 'pre-wrap' }}
                dangerouslySetInnerHTML={{ __html: body ? renderMarkdown(body) : '<span style="opacity:0.38">Jot down a quick thought…</span>' }}
              />
            )
          ) : (
            <div className="checklist-container" data-no-drag onMouseDown={e => e.stopPropagation()}>
              {checklistItems.map((item, idx) => (
                <div key={idx} className="checklist-item-row" data-no-drag onMouseDown={e => e.stopPropagation()}>
                  <button
                    type="button"
                    className={`checklist-checkbox ${item.isDone ? 'is-checked' : ''}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      void toggleChecklistItem(idx);
                    }}
                    title={item.isDone ? 'Mark incomplete' : 'Mark complete'}
                  >
                    {item.isDone && <Check size={12} strokeWidth={3} />}
                  </button>
                  <input
                    className={`checklist-item-text ${item.isDone ? 'is-done' : ''} ${fontClass}`}
                    value={item.text}
                    onChange={e => handleEditChecklistItem(idx, e.target.value)}
                    onBlur={handleFinishEditChecklistItem}
                  />
                  <button
                    type="button"
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
          <div className="note-popup-overlay" data-no-drag onMouseDown={e => e.stopPropagation()} onClick={() => setShowFontModal(false)}>
            <div className="note-popup-card" data-no-drag onMouseDown={e => e.stopPropagation()} onClick={e => e.stopPropagation()} style={{ maxWidth: 310 }}>
              <h3 className="popup-title">
                <Type size={16} color="var(--brand-violet)" /> Choose Note Font
              </h3>
              <p style={{ margin: '0 0 10px 0', fontSize: 11, color: 'var(--ink-secondary)', lineHeight: 1.4 }}>
                Pick a font style for this sticky note (cute cursive, serif, clean sans, etc.):
              </p>
              <div className="font-preset-list" data-no-drag onMouseDown={e => e.stopPropagation()}>
                {NOTE_FONTS.map(f => (
                  <button
                    key={f.id}
                    type="button"
                    className={`font-preset-item ${selectedFont === f.id ? 'is-selected' : ''}`}
                    onClick={(e) => {
                      e.stopPropagation();
                      handleSelectFont(f.id);
                    }}
                  >
                    <div className="font-item-info">
                      <span className={`font-item-name ${f.class}`}>{f.name}</span>
                      <span className="font-item-category">{f.category}</span>
                    </div>
                    <span className={`font-preview-sample ${f.class}`}>{f.sample}</span>
                  </button>
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

        {/* Voice Speech-to-Text Modal Overlay */}
        {showVoiceModal && (
          <div className="note-popup-overlay" data-no-drag onMouseDown={e => e.stopPropagation()} onClick={() => setShowVoiceModal(false)}>
            <div className="note-popup-card" data-no-drag onMouseDown={e => e.stopPropagation()} onClick={e => e.stopPropagation()} style={{ maxWidth: 330 }}>
              <h3 className="popup-title">
                <Mic size={16} color="var(--brand-violet)" /> Voice Speech Dictation
              </h3>
              <div style={{ background: 'var(--brand-soft)', border: '1px solid rgba(138, 75, 255, 0.25)', borderRadius: 10, padding: '10px 12px', margin: '8px 0 12px' }}>
                <p style={{ margin: 0, fontSize: 12, fontWeight: 700, color: 'var(--brand-violet)', display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span>🎙️ Windows Voice Typing</span>
                </p>
                <p style={{ margin: '4px 0 0 0', fontSize: 11, color: 'var(--ink-secondary)', lineHeight: 1.45 }}>
                  Press <kbd style={{ background: '#FFF', padding: '2px 6px', borderRadius: 4, border: '1px solid #CCC', fontWeight: 800, color: '#333' }}>Win + H</kbd> on your keyboard to instantly dictate anywhere in this note using Windows AI voice!
                </p>
              </div>

              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--ink-secondary)', display: 'block', marginBottom: 4 }}>
                Or quick type / paste voice note text:
              </span>
              <textarea
                className="popup-input"
                style={{ height: 68, resize: 'none' }}
                placeholder="Speak or paste text here…"
                value={voiceInputDraft}
                onChange={e => setVoiceInputDraft(e.target.value)}
                autoFocus
              />

              <div className="popup-actions" style={{ marginTop: 10 }}>
                <button className="popup-btn-secondary" onClick={() => setShowVoiceModal(false)}>
                  Cancel
                </button>
                <button
                  className="popup-btn-primary"
                  onClick={() => {
                    if (voiceInputDraft.trim()) {
                      const sep = body.length > 0 && !body.endsWith(' ') && !body.endsWith('\n') ? '\n' : '';
                      const next = body + sep + voiceInputDraft.trim();
                      setBody(next);
                      pushHistory(next);
                      void updateFields({ body: next });
                      setVoiceInputDraft('');
                      showToast('Voice note added!');
                    }
                    setShowVoiceModal(false);
                  }}
                >
                  Insert Note
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
  const [widthFactor, setWidthFactor] = useState<number>(() => {
    return parseFloat(localStorage.getItem('sidenote_width_factor') || '0.85');
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
  const [pairingPrompt, setPairingPrompt] = useState<{ pin: string; deviceName: string } | null>(null);
  const [pairedToast, setPairedToast] = useState<string | null>(null);
  const [trustedPeersList, setTrustedPeersList] = useState<Array<{ deviceId: string; label: string; publicKey?: string }>>([]);

  const loadTrustedPeers = () => {
    if (native) {
      invoke<Array<{ deviceId: string; label: string; publicKey?: string }>>('trusted_peers')
        .then(setTrustedPeersList)
        .catch(() => {});
    }
  };

  useEffect(() => {
    if (native) {
      invoke<string>('local_device_id')
        .then(setDeviceId)
        .catch(() => {});
      invoke<string>('local_device_public_key')
        .then(k => invoke<string>('public_key_fingerprint', { publicKey: k }))
        .then(setFingerprint)
        .catch(() => {});
      loadTrustedPeers();

      const unlistenPin = listen<{ pin: string; deviceName: string; deviceId: string }>('sidenote_pairing_pin', evt => {
        setPairingPrompt({ pin: evt.payload.pin, deviceName: evt.payload.deviceName });
      });
      const unlistenPaired = listen<{ deviceId: string; deviceName: string }>('sidenote_paired', evt => {
        setPairedToast(`Connected to ${evt.payload.deviceName || 'Android Mobile'}!`);
        loadTrustedPeers();
        setTimeout(() => setPairedToast(null), 6000);
      });

      return () => {
        unlistenPin.then(f => f());
        unlistenPaired.then(f => f());
      };
    }
  }, []);

  const handleGeneratePin = async () => {
    try {
      const pin = await invoke<string>('lan_get_pairing_pin');
      setPairingPrompt({ pin, deviceName: 'Android Mobile' });
    } catch (e) {
      console.error(e);
    }
  };

  const handleUntrustPeer = async (peerId: string) => {
    try {
      await invoke('untrust_peer', { deviceId: peerId });
      loadTrustedPeers();
    } catch (e) {
      console.error(e);
    }
  };

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

  const [isAppMaximized, setIsAppMaximized] = useState(false);

  useEffect(() => {
    if (native) {
      getCurrentWindow().isMaximized().then(setIsAppMaximized).catch(() => {});
    }
  }, []);

  const handleAppMinimize = async () => {
    if (native) {
      try {
        await getCurrentWindow().minimize();
      } catch (e) {
        console.warn('App minimize error:', e);
      }
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_library'));
    }
  };

  const handleAppToggleMaximize = async () => {
    if (native) {
      try {
        const w = getCurrentWindow();
        const max = await w.isMaximized();
        if (max) {
          await w.unmaximize();
          setIsAppMaximized(false);
        } else {
          await w.maximize();
          setIsAppMaximized(true);
        }
      } catch (e) {
        console.warn('App maximize error:', e);
        try {
          await (getCurrentWindow() as any).toggleMaximize();
        } catch {}
        setIsAppMaximized(prev => !prev);
      }
    } else {
      setIsAppMaximized(prev => !prev);
    }
  };

  const handleAppClose = async () => {
    if (native) {
      await getCurrentWindow().hide();
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_library'));
    }
  };

  const handleLibraryDragMouseDown = (e: React.MouseEvent) => {
    if (e.button === 0) {
      const target = e.target as HTMLElement;
      if (!target.closest('button, input, textarea, a, .window-controls-group, [data-no-drag]')) {
        if (native) {
          getCurrentWindow().startDragging().catch(console.warn);
        }
      }
    }
  };

  return (
    <div className="library-window">
      {/* Header with Drag Region & Window Controls */}
      <header className="library-header" data-tauri-drag-region onMouseDown={handleLibraryDragMouseDown}>
        <div className="library-brand-block" data-tauri-drag-region>
          <BrandBadge />
        </div>

        <nav className="library-tabs-nav" data-no-drag onMouseDown={e => e.stopPropagation()}>
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

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }} data-no-drag onMouseDown={e => e.stopPropagation()}>
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

          {/* Window Control Buttons: Minimize, Full Size / Maximize, Close */}
          <div className="window-controls-group">
            <button
              className="win-control-btn"
              title="Minimize window"
              onClick={handleAppMinimize}
            >
              <Minus size={15} />
            </button>
            <button
              className="win-control-btn"
              title={isAppMaximized ? "Restore Size" : "Full Size / Maximize"}
              onClick={handleAppToggleMaximize}
            >
              {isAppMaximized ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
            </button>
            <button
              className="win-control-btn is-close"
              title="Close Note App"
              onClick={handleAppClose}
            >
              <X size={16} />
            </button>
          </div>
        </div>
      </header>

      {/* Main Body */}
      <div className="library-content-body">
        {tab === 'backup' ? (
          <div className="sync-pane-card">
            <h2 style={{ margin: '0 0 8px 0', fontSize: 18, fontWeight: 800 }}>Backup, Preferences & Desktop Tools</h2>
            <p style={{ margin: '0 0 18px 0', fontSize: 13, color: 'var(--ink-secondary)', lineHeight: 1.6 }}>
              Manage full JSON backups, switch screen edge dock position, and test audio alerts.
            </p>

            {/* Edge Dock Width Setting */}
            <div className="backup-section-card">
              <div className="backup-info">
                <h4>Dock Size & Card Width</h4>
                <p>Adjust the width of expanded note cards in the peeking edge dock rail.</p>
              </div>
              <div className="backup-actions" style={{ display: 'flex', gap: 8 }}>
                <button
                  className={widthFactor <= 0.7 ? 'primary-btn' : 'outline-btn'}
                  onClick={() => {
                    setWidthFactor(0.65);
                    localStorage.setItem('sidenote_width_factor', '0.65');
                    setBackupStatus('Dock set to Compact');
                  }}
                >
                  Compact
                </button>
                <button
                  className={widthFactor > 0.7 && widthFactor < 1.0 ? 'primary-btn' : 'outline-btn'}
                  onClick={() => {
                    setWidthFactor(0.85);
                    localStorage.setItem('sidenote_width_factor', '0.85');
                    setBackupStatus('Dock set to Standard');
                  }}
                >
                  Standard
                </button>
                <button
                  className={widthFactor >= 1.0 ? 'primary-btn' : 'outline-btn'}
                  onClick={() => {
                    setWidthFactor(1.15);
                    localStorage.setItem('sidenote_width_factor', '1.15');
                    setBackupStatus('Dock set to Wide');
                  }}
                >
                  Wide
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
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
              <div>
                <h2 style={{ margin: '0 0 6px 0', fontSize: 18, fontWeight: 800 }}>Seamless Wi-Fi Notes Sync</h2>
                <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-secondary)', lineHeight: 1.5 }}>
                  Real-time notes sync between your Android mobile & Windows PC over Wi-Fi without cloud or accounts.
                </p>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', background: 'rgba(34, 197, 94, 0.12)', border: '1px solid rgba(34, 197, 94, 0.3)', borderRadius: 20 }}>
                <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#22c55e', display: 'inline-block' }} />
                <span style={{ fontSize: 12, fontWeight: 700, color: '#16a34a' }}>Sync Active</span>
              </div>
            </div>

            {pairedToast && (
              <div style={{ background: 'rgba(34, 197, 94, 0.15)', border: '1px solid #22c55e', borderRadius: 12, padding: '10px 16px', marginBottom: 16, color: '#15803d', fontWeight: 700, fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Check size={16} /> {pairedToast}
              </div>
            )}

            {/* 4-Digit Pairing Code Banner / Trigger */}
            {pairingPrompt ? (
              <div style={{ background: 'rgba(138, 75, 255, 0.08)', border: '2px solid var(--brand-violet)', borderRadius: 16, padding: '24px 20px', textAlign: 'center', marginBottom: 20 }}>
                <span style={{ fontSize: 11, fontWeight: 800, textTransform: 'uppercase', letterSpacing: 1.2, color: 'var(--brand-violet)' }}>
                  Mobile Pairing Request
                </span>
                <h3 style={{ margin: '8px 0 4px', fontSize: 20, fontWeight: 800 }}>Enter This PIN on Your Phone</h3>
                <p style={{ margin: '0 0 16px', fontSize: 13, color: 'var(--ink-secondary)' }}>
                  Open SideNote on your Android phone → Settings → Connect with Windows PC
                </p>
                <div style={{ display: 'inline-flex', gap: 14, padding: '14px 28px', background: 'var(--surface-primary)', borderRadius: 14, border: '2px solid var(--surface-border)', fontSize: 36, fontWeight: 900, letterSpacing: 10, color: 'var(--brand-violet)', fontFamily: 'monospace' }}>
                  {pairingPrompt.pin}
                </div>
                <div style={{ marginTop: 16 }}>
                  <button className="outline-btn" style={{ fontSize: 12, padding: '6px 16px' }} onClick={() => setPairingPrompt(null)}>
                    Dismiss Code
                  </button>
                </div>
              </div>
            ) : (
              <div style={{ background: 'var(--surface-field)', border: '1px solid var(--surface-border)', borderRadius: 16, padding: '18px 20px', marginBottom: 20, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
                <div>
                  <h4 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Pair an Android Phone</h4>
                  <p style={{ margin: 0, fontSize: 13, color: 'var(--ink-secondary)' }}>
                    No camera or QR codes needed. Generates a 4-digit code to pair instantly.
                  </p>
                </div>
                <button className="primary-btn" onClick={handleGeneratePin} style={{ padding: '8px 16px' }}>
                  <Wifi size={15} /> Show Pairing PIN
                </button>
              </div>
            )}

            {/* Paired Devices List */}
            <div style={{ marginBottom: 20 }}>
              <strong style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: 0.8, color: 'var(--ink-secondary)', display: 'block', marginBottom: 10 }}>
                Paired Mobile Devices ({trustedPeersList.length})
              </strong>
              {trustedPeersList.length === 0 ? (
                <div style={{ padding: '14px 18px', background: 'var(--surface-field)', borderRadius: 12, border: '1px dashed var(--surface-border)', fontSize: 13, color: 'var(--ink-secondary)' }}>
                  No phones paired yet. Open SideNote on your phone to connect over Wi-Fi!
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {trustedPeersList.map(peer => (
                    <div key={peer.deviceId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', background: 'var(--surface-field)', borderRadius: 12, border: '1px solid var(--surface-border)' }}>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: 14 }}>📱 {peer.label || 'Android Device'}</div>
                        <div style={{ fontSize: 11, color: 'var(--ink-secondary)', marginTop: 2 }}>ID: {peer.deviceId.slice(0, 8)}... • Wi-Fi Connected</div>
                      </div>
                      <button className="outline-btn" style={{ fontSize: 12, padding: '4px 10px', color: '#ef4444' }} onClick={() => handleUntrustPeer(peer.deviceId)}>
                        Remove
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Direct Connect / Info */}
            <div style={{ marginTop: 24, paddingTop: 16, borderTop: '1px solid var(--surface-border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12, color: 'var(--ink-secondary)' }}>
                <span>TCP Port: <strong>43861</strong> • Discovery: <strong>43862</strong></span>
                <span>Device Fingerprint: <strong>{fingerprint.slice(0, 14)}...</strong></span>
              </div>
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
      const detail = e.detail;
      const n = (detail?.note || detail) as Note;
      const posX = typeof detail?.x === 'number' ? Math.max(30, Math.min(window.innerWidth - 380, detail.x)) : 70;
      const posY = typeof detail?.y === 'number' ? Math.max(30, Math.min(window.innerHeight - 480, detail.y)) : 50;
      setActiveFloatingNotes(prev => {
        const filtered = prev.filter(x => x.id !== n.id);
        return [...filtered, { ...n, x: posX, y: posY } as any];
      });
    };
    const handleCloseFloat = (e: any) => {
      const id = e.detail as string;
      setActiveFloatingNotes(prev => prev.filter(x => x.id !== id));
    };
    const handleOpenLib = () => setShowLibraryModal(true);
    const handleCloseLib = () => setShowLibraryModal(false);

    window.addEventListener('sidenote_preview_float', handleFloat);
    window.addEventListener('sidenote_preview_close_float', handleCloseFloat);
    window.addEventListener('sidenote_preview_library', handleOpenLib);
    window.addEventListener('sidenote_preview_close_library', handleCloseLib);

    return () => {
      window.removeEventListener('sidenote_preview_float', handleFloat);
      window.removeEventListener('sidenote_preview_close_float', handleCloseFloat);
      window.removeEventListener('sidenote_preview_library', handleOpenLib);
      window.removeEventListener('sidenote_preview_close_library', handleCloseLib);
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
      {activeFloatingNotes.map((fn: any, i: number) => {
        const left = typeof fn.x === 'number' ? fn.x : (70 + (i % 3) * 360);
        const top = typeof fn.y === 'number' ? fn.y : (50 + (i % 3) * 60);
        return (
          <div
            key={fn.id}
            style={{
              position: 'absolute',
              top,
              left,
              width: 340,
              height: 430,
              zIndex: 20 + i,
            }}
            onPointerDown={e => {
              const target = e.target as HTMLElement;
              if (target.closest('button, input, textarea, a, .checklist-checkbox, .font-preset-item, .preset-btn, .color-swatch-dot')) {
                return;
              }
              const startX = e.clientX;
              const startY = e.clientY;
              const initialLeft = left;
              const initialTop = top;
              const onMove = (ev: PointerEvent) => {
                const nextX = Math.max(10, Math.min(window.innerWidth - 360, initialLeft + ev.clientX - startX));
                const nextY = Math.max(10, Math.min(window.innerHeight - 450, initialTop + ev.clientY - startY));
                setActiveFloatingNotes(prev => prev.map(item => item.id === fn.id ? { ...item, x: nextX, y: nextY } : item));
              };
              const onUp = () => {
                window.removeEventListener('pointermove', onMove);
                window.removeEventListener('pointerup', onUp);
              };
              window.addEventListener('pointermove', onMove);
              window.addEventListener('pointerup', onUp);
            }}
          >
            <FloatingNoteCard noteId={fn.id} />
          </div>
        );
      })}

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
