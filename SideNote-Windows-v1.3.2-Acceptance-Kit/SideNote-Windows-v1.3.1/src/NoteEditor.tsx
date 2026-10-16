import React, { useEffect, useRef, useState } from 'react';
import { EditorContent, useEditor, type Editor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import UnderlineMark from '@tiptap/extension-underline';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { Markdown } from '@tiptap/markdown';
import { Bold, Italic, Underline, Heading, List, ListOrdered, CheckSquare, Link, Clock, Copy, RotateCcw, RotateCw, X } from 'lucide-react';

export function normalizeNoteMarkdown(value: string, checklist = false) {
  const hasTasks = /^\s*(?:[-*+] )?\[[ xX]\](?:\s|$)/m.test(value);
  return value.split('\n').map(line => {
    if (/^\s*\[[ xX]\](?:\s|$)/.test(line)) return line.replace(/^(\s*)(\[[ xX]\])(?:\s|$)/, '$1- $2 ');
    if (checklist && !hasTasks && line.trim()) return '- [ ] ' + line;
    return line.replace(/^(\s*)• /, '$1- ');
  }).join('\n');
}
export function normalizeLink(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const candidate = /^[a-z][\w+.-]*:/i.test(trimmed) ? trimmed : 'https://' + trimmed;
  try {
    const url = new URL(candidate);
    return ['https:', 'http:', 'mailto:'].includes(url.protocol) && (url.protocol === 'mailto:' ? !!url.pathname : !!url.hostname) ? url.href : null;
  } catch { return null; }
}
export function insertNoteText(editor: Editor | null, text: string) {
  if (editor && text) editor.chain().focus().insertContent({ type: 'text', text }).run();
}

type Props = {
  value: string;
  checklist?: boolean;
  compact?: boolean;
  fontClass?: string;
  onChange: (value: string, checklist: boolean) => void;
  onReady?: (editor: Editor) => void;
  onOpenLink: (url: string) => void;
  extraTools?: React.ReactNode;
};

export function NoteEditor({ value, checklist, compact, fontClass = '', onChange, onReady, onOpenLink, extraTools }: Props) {
  const changeRef = useRef(onChange);
  const openRef = useRef(onOpenLink);
  changeRef.current = onChange;
  openRef.current = onOpenLink;
  const lastValue = useRef(value);
  const [, redraw] = useState(0);
  const [link, setLink] = useState<{ from: number; to: number; label: string; url: string } | null>(null);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const editor = useEditor({
    extensions: [
      StarterKit.configure({ underline: false, link: { openOnClick: false, defaultProtocol: 'https', protocols: ['mailto'], HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' } } }),
      UnderlineMark.extend({ renderMarkdown: (node, helpers) => `<u>${helpers.renderChildren(node)}</u>` }),
      TaskList, TaskItem.configure({ nested: true }),
      Markdown.configure({ markedOptions: { gfm: true, breaks: true } }),
    ],
    content: normalizeNoteMarkdown(value, checklist),
    contentType: 'markdown',
    editorProps: {
      attributes: { class: 'note-editor-content', 'aria-label': 'Note body', role: 'textbox', 'aria-multiline': 'true', spellcheck: 'true' },
      handleDOMEvents: { click: (_view, event) => {
        const anchor = (event.target as HTMLElement).closest('a');
        if (!anchor) return false;
        event.preventDefault();
        event.stopPropagation();
        const url = normalizeLink(anchor.getAttribute('href') || '');
        if (url) openRef.current(url);
        return true;
      } },
    },
    onUpdate: ({ editor }) => {
      const markdown = editor.getMarkdown().replace(/^(\s*)- \[([ xX])\]/gm, '$1[$2]');
      lastValue.current = markdown;
      let tasks = false;
      editor.state.doc.descendants(node => { if (node.type.name === 'taskList') tasks = true; });
      changeRef.current(markdown, tasks);
    },
    onTransaction: () => redraw(n => n + 1),
  });
  useEffect(() => { if (editor) onReady?.(editor); }, [editor, onReady]);
  useEffect(() => {
    if (!editor || value === lastValue.current) return;
    // Polling acknowledgements must not reset selection or undo history while typing.
    if (editor.isFocused) return;
    lastValue.current = value;
    editor.commands.setContent(normalizeNoteMarkdown(value, checklist), { contentType: 'markdown', emitUpdate: false });
  }, [value, checklist, editor]);
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(''), 2500);
    return () => clearTimeout(timer);
  }, [message]);
  if (!editor) return null;
  const openLink = () => {
    if (editor.isActive('link')) editor.commands.extendMarkRange('link');
    const { from, to } = editor.state.selection;
    const attrs = editor.getAttributes('link');
    setError('');
    setLink({ from, to, label: editor.state.doc.textBetween(from, to, ' '), url: attrs.href || '' });
  };
  const applyLink = (event: React.FormEvent) => {
    event.preventDefault();
    if (!link) return;
    const url = normalizeLink(link.url);
    if (!url) { setError('Enter a valid http, https or email link.'); return; }
    const chain = editor.chain().focus().setTextSelection({ from: link.from, to: link.to });
    const selected = editor.state.doc.textBetween(link.from, link.to, ' ');
    if (link.from === link.to || link.label !== selected) {
      chain.insertContent({ type: 'text', text: link.label || url, marks: [{ type: 'link', attrs: { href: url } }] }).run();
    } else chain.setLink({ href: url }).run();
    setLink(null);
  };
  const tools = [
    { title: 'Undo (Ctrl+Z)', icon: RotateCcw, action: () => editor.chain().focus().undo().run(), disabled: !editor.can().undo() },
    { title: 'Redo (Ctrl+Y)', icon: RotateCw, action: () => editor.chain().focus().redo().run(), disabled: !editor.can().redo() },
    { title: 'Bold (Ctrl+B)', icon: Bold, active: editor.isActive('bold'), action: () => editor.chain().focus().toggleBold().run() },
    { title: 'Italic (Ctrl+I)', icon: Italic, active: editor.isActive('italic'), action: () => editor.chain().focus().toggleItalic().run() },
    { title: 'Underline (Ctrl+U)', icon: Underline, active: editor.isActive('underline'), action: () => editor.chain().focus().toggleUnderline().run() },
    { title: 'Heading', icon: Heading, active: editor.isActive('heading'), action: () => editor.chain().focus().toggleHeading({ level: 3 }).run() },
    { title: 'Bullet list', icon: List, active: editor.isActive('bulletList'), action: () => editor.chain().focus().toggleBulletList().run() },
    { title: 'Numbered list', icon: ListOrdered, active: editor.isActive('orderedList'), action: () => editor.chain().focus().toggleOrderedList().run() },
    { title: 'Checklist', icon: CheckSquare, active: editor.isActive('taskList'), action: () => editor.chain().focus().toggleTaskList().run() },
    { title: 'Insert or edit link', icon: Link, active: editor.isActive('link'), action: openLink },
    { title: 'Insert timestamp', icon: Clock, action: () => insertNoteText(editor, new Date().toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })) },
    { title: 'Copy note text', icon: Copy, action: async () => {
      try { await navigator.clipboard.writeText(editor.getText()); setMessage('Copied'); }
      catch { setMessage('Could not copy. Select text and press Ctrl+C.'); }
    } },
  ];
  return <div className={`note-editor ${compact ? 'note-editor--compact txt' : ''} ${fontClass}`} data-no-drag
    onMouseDown={e => e.stopPropagation()}
    onKeyDown={e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); openLink(); }
    }}>
    <div className="note-editor-toolbar" role="toolbar" aria-label="Note formatting">
      {tools.map(({ title, icon: Icon, action, active, disabled }) => <button key={title} type="button" title={title} aria-label={title}
        aria-pressed={active} disabled={disabled} onMouseDown={e => e.preventDefault()} onClick={() => void action()}><Icon size={15} /></button>)}
      {extraTools}
    </div>
    <EditorContent editor={editor} className="note-editor-document" />
    {message && <span className="note-editor-message" role="status">{message}</span>}
    {link && <form className="note-editor-link" onSubmit={applyLink} onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); setLink(null); editor.commands.focus(); } }}>
      <div className="note-editor-link-heading">Web link<button type="button" aria-label="Close link dialog" onClick={() => setLink(null)}><X size={14} /></button></div>
      <label>Text<input value={link.label} onChange={e => setLink({ ...link, label: e.target.value })} /></label>
      <label>URL<input autoFocus placeholder="https://example.com" value={link.url} onChange={e => setLink({ ...link, url: e.target.value })} /></label>
      {error && <span role="alert">{error}</span>}
      <div className="note-editor-link-actions">
        <button type="button" onClick={() => { editor.chain().focus().setTextSelection({ from: link.from, to: link.to }).extendMarkRange('link').unsetLink().run(); setLink(null); }}>Remove link</button>
        <button type="submit">Save link</button>
      </div>
    </form>}
  </div>;
}
