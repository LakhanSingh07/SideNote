import React, { useEffect, useMemo, useState, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { invoke } from '@tauri-apps/api/core';
import { WebviewWindow } from '@tauri-apps/api/webviewWindow';
import { getCurrentWindow, LogicalPosition, LogicalSize, availableMonitors, currentMonitor } from '@tauri-apps/api/window';
import {
  Plus, Search, Pin, Archive, Trash2, Settings, Minus, X,
  StickyNote, ChevronRight, Check, CheckSquare, Square,
  Moon, Sun, RefreshCw, Copy, Wifi, ShieldCheck, Folder, Sparkles, Move
} from 'lucide-react';
import './style.css';

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
    resizable: true,
    focus: true,
  });
  w.once('tauri://error', e => console.error('Failed to spawn window', e));
  return true;
}

async function openFloatingNote(n: Note) {
  if (native) {
    const label = placementKey(n.id);
    const old = await safePlacement(label);
    const p = old ? await clampPlacement(old) : null;
    await spawnWindow(label, '/?view=note&id=' + encodeURIComponent(n.id), p?.width || 310, p?.height || 330, p?.x, p?.y);
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
        <StickyNote size={19} strokeWidth={2.4} />
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
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const busyRef = useRef(false);

  // Filter 4 recent unarchived notes for peeking
  const peekingNotes = useMemo(() => {
    return notes
      .filter(n => !n.archived)
      .sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt)
      .slice(0, 4);
  }, [notes]);

  // Position at right screen edge and handle smooth resize
  const adjustDockWidth = async (open: boolean) => {
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
      const right = (mon.position.x / scale) + screenWidth;
      const targetY = Math.max(50, Math.min(curY, screenHeight - targetHeight - 40));

      await w.setSize(new LogicalSize(targetWidth, targetHeight));
      await w.setPosition(new LogicalPosition(right - targetWidth, targetY));
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
    // Snap to right edge on mount
    void adjustDockWidth(false);
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

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
    <div className="dock-container" data-theme={theme}>
      <aside
        className={`dock-rail ${expanded ? 'is-expanded' : ''}`}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
      >
        {/* Header Handle */}
        <header className="dock-header" onClick={() => void adjustDockWidth(!expanded)}>
          <BrandBadge />
          <button className="dock-collapse-btn" title="Collapse" onClick={() => void adjustDockWidth(false)}>
            <ChevronRight size={16} />
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
                  </>
                ) : (
                  <>
                    <div className="peek-card-header">
                      <span className="peek-card-tag">{n.isChecklist ? 'Checklist' : 'Note'}</span>
                      {n.pinned && <Pin size={12} fill="currentColor" />}
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
function FloatingNoteCard() {
  const notes = useNotesList();
  const note = notes.find(n => n.id === targetNoteId);

  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [isChecklist, setIsChecklist] = useState(false);
  const [pinned, setPinned] = useState(false);
  const [color, setColor] = useState(0);
  const [newItemText, setNewItemText] = useState('');

  useEffect(() => {
    if (note) {
      setTitle(note.title);
      setBody(note.body);
      setIsChecklist(note.isChecklist);
      setPinned(note.pinned);
      setColor(note.color);
    }
  }, [note?.id]);

  // Persist placement on move
  useEffect(() => {
    if (!native || !targetNoteId) return;
    let timeout: ReturnType<typeof setTimeout> | null = null;
    let unlisten: undefined | (() => void);
    let active = true;

    getCurrentWindow()
      .onMoved(() => {
        if (timeout) clearTimeout(timeout);
        timeout = setTimeout(() => void persistPlacement(placementKey(targetNoteId), 310, 330), 400);
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
  }, []);

  if (!note) {
    return <div style={{ padding: 20, fontSize: 13, color: '#888' }}>Loading sticky note...</div>;
  }

  const colorMeta = NOTE_COLORS[color] || NOTE_COLORS[0];

  const updateFields = async (updates: Partial<Note>) => {
    const updated: Note = {
      ...note,
      ...updates,
      updatedAt: Date.now(),
    };
    await saveNote(updated);
  };

  const handleTitleBlur = () => {
    if (title !== note.title) void updateFields({ title });
  };

  const handleBodyBlur = () => {
    if (body !== note.body) void updateFields({ body });
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
  };

  const handleMinimize = async () => {
    if (native) {
      await persistPlacement(placementKey(note.id));
      await getCurrentWindow().close();
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }));
    }
  };

  const handleClose = async () => {
    if (native) {
      await persistPlacement(placementKey(note.id));
      await getCurrentWindow().close();
    } else {
      window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }));
    }
  };

  const handleArchive = async () => {
    await updateFields({ archived: true });
    await handleClose();
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
    await updateFields({ body: newBody });
  };

  const addChecklistItem = async () => {
    if (!newItemText.trim()) return;
    const newBody = body ? `${body}\n[ ] ${newItemText.trim()}` : `[ ] ${newItemText.trim()}`;
    setBody(newBody);
    setNewItemText('');
    await updateFields({ body: newBody });
  };

  const deleteChecklistItem = async (index: number) => {
    const items = checklistItems.filter((_, i) => i !== index);
    const newBody = items.map(item => `[${item.isDone ? 'x' : ' '}] ${item.text}`).join('\n');
    setBody(newBody);
    await updateFields({ body: newBody });
  };

  return (
    <div className="floating-card-wrapper">
      <div
        className="floating-sticky-note"
        style={{
          background: colorMeta.bg,
          color: colorMeta.ink,
          borderColor: colorMeta.border,
        }}
      >
        {/* Top Accent Tape with Drag Region */}
        <div className="sticky-top-tape" data-tauri-drag-region>
          <div className="sticky-badge-group">
            <span className="sticky-badge">{isChecklist ? 'Checklist' : 'Sticky Note'}</span>
          </div>

          <div className="sticky-actions">
            {/* Checklist Toggle */}
            <button
              className={`sticky-icon-btn ${isChecklist ? 'is-active' : ''}`}
              title="Toggle checklist"
              onClick={async () => {
                const next = !isChecklist;
                setIsChecklist(next);
                await updateFields({ isChecklist: next });
              }}
            >
              <CheckSquare size={14} />
            </button>

            {/* Pin Toggle */}
            <button
              className={`sticky-icon-btn ${pinned ? 'is-active' : ''}`}
              title={pinned ? 'Unpin from top' : 'Pin on top'}
              onClick={togglePin}
            >
              <Pin size={14} fill={pinned ? 'currentColor' : 'none'} />
            </button>

            {/* Minimize to Edge Dock */}
            <button className="sticky-icon-btn" title="Fold to edge dock" onClick={handleMinimize}>
              <Minus size={15} />
            </button>

            {/* Close / Archive */}
            <button className="sticky-icon-btn" title="Archive note" onClick={handleArchive}>
              <X size={15} />
            </button>
          </div>
        </div>

        {/* Content Area */}
        <div className="sticky-body-area">
          <input
            className="sticky-title-input"
            placeholder="Title..."
            value={title}
            onChange={e => setTitle(e.target.value)}
            onBlur={handleTitleBlur}
            maxLength={2000}
          />

          {!isChecklist ? (
            <textarea
              className="sticky-textarea"
              placeholder="Jot down a quick thought..."
              value={body}
              onChange={e => setBody(e.target.value)}
              onBlur={handleBodyBlur}
            />
          ) : (
            <div className="checklist-container">
              {checklistItems.map((item, idx) => (
                <div key={idx} className="checklist-item-row">
                  <div
                    className={`checklist-checkbox ${item.isDone ? 'is-checked' : ''}`}
                    onClick={() => void toggleChecklistItem(idx)}
                  >
                    {item.isDone && <Check size={12} strokeWidth={3} />}
                  </div>
                  <span className={`checklist-item-text ${item.isDone ? 'is-done' : ''}`}>
                    {item.text}
                  </span>
                  <button
                    style={{ border: 'none', background: 'transparent', cursor: 'pointer', opacity: 0.3 }}
                    onClick={() => void deleteChecklistItem(idx)}
                  >
                    <X size={12} />
                  </button>
                </div>
              ))}

              <div className="checklist-add-row">
                <Plus size={14} />
                <input
                  className="checklist-add-input"
                  placeholder="New item (press Enter)..."
                  value={newItemText}
                  onChange={e => setNewItemText(e.target.value)}
                  onKeyDown={e => {
                    if (e.key === 'Enter') void addChecklistItem();
                  }}
                />
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
      </div>
    </div>
  );
}

// ==============================================================
// 4. SECONDARY MANAGEMENT & SYNC HUB
// ==============================================================
function LibraryView() {
  const notes = useNotesList();
  const [tab, setTab] = useState<'all' | 'pinned' | 'checklists' | 'archived' | 'sync'>('all');
  const [searchQuery, setSearchQuery] = useState('');

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
        {tab !== 'sync' ? (
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
                        {n.pinned && <Pin size={13} fill="currentColor" />}
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
      <div style={{ position: 'absolute', top: 60, left: 80, width: 320, height: 340 }}>
        {activeFloatingNotes.length > 0 && <FloatingNoteSim note={activeFloatingNotes[0]} />}
      </div>

      {activeFloatingNotes.length > 1 && (
        <div style={{ position: 'absolute', top: 120, left: 440, width: 320, height: 340 }}>
          <FloatingNoteSim note={activeFloatingNotes[1]} />
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
            style={{ width: 840, height: 580, borderRadius: 20, overflow: 'hidden' }}
            onClick={e => e.stopPropagation()}
          >
            <LibraryView />
          </div>
        </div>
      )}
    </div>
  );
}

function FloatingNoteSim({ note }: { note: Note }) {
  const colorMeta = NOTE_COLORS[note.color] || NOTE_COLORS[0];
  return (
    <div
      className="floating-sticky-note"
      style={{
        background: colorMeta.bg,
        color: colorMeta.ink,
        borderColor: colorMeta.border,
        height: '100%',
      }}
    >
      <div className="sticky-top-tape">
        <span className="sticky-badge">{note.isChecklist ? 'Checklist' : 'Sticky Note'}</span>
        <div className="sticky-actions">
          <button className="sticky-icon-btn" title="Pin">
            <Pin size={13} fill={note.pinned ? 'currentColor' : 'none'} />
          </button>
          <button
            className="sticky-icon-btn"
            title="Fold"
            onClick={() => window.dispatchEvent(new CustomEvent('sidenote_preview_close_float', { detail: note.id }))}
          >
            <Minus size={14} />
          </button>
        </div>
      </div>
      <div className="sticky-body-area">
        <h3 style={{ margin: '0 0 8px 0', fontSize: 16, fontWeight: 800 }}>{note.title}</h3>
        <p style={{ margin: 0, fontSize: 13, lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>{note.body}</p>
      </div>
      <div className="sticky-footer-bar">
        <div className="color-swatches-row">
          {NOTE_COLORS.map((c, i) => (
            <div key={i} className="color-swatch-dot" style={{ background: c.bg }} />
          ))}
        </div>
        <span className="sticky-footer-meta">Desktop Note</span>
      </div>
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
