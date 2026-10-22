import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query'
import { errorMessage } from '../api/client'
import { statusTone } from '../lib/format'

// ---- Buttons --------------------------------------------------------------------------------------------------

type Kind = 'primary' | 'paper' | 'outline' | 'ghost' | 'danger'

export function Button({ kind = 'outline', small, busy, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & { kind?: Kind; small?: boolean; busy?: boolean }) {
  const cls = ['btn', kind === 'outline' ? '' : kind, small ? 'sm' : '', rest.className ?? ''].filter(Boolean).join(' ')
  return (
    <button type="button" {...rest} className={cls} disabled={rest.disabled || busy} aria-busy={busy || undefined}>
      {busy ? 'Working…' : children}
    </button>
  )
}

// ---- Fields ---------------------------------------------------------------------------------------------------

export function Field({ label, hint, error, children, className }: { label: string; hint?: ReactNode; error?: string | null; children: (id: string) => ReactNode; className?: string }) {
  const id = useId()
  return (
    <div className={`field ${className ?? ''}`}>
      <label htmlFor={id} className="label">{label}</label>
      {children(id)}
      {error ? <span className="error-text" role="alert">{error}</span> : hint ? <span className="hint">{hint}</span> : null}
    </div>
  )
}

// ---- Status and tags ------------------------------------------------------------------------------------------

export function StatusTag({ status, title }: { status: string; title?: string }) {
  return <span className={`tag ${statusTone(status)}`}>{title ?? status.replace(/_/g, ' ')}</span>
}

export const Tag = ({ children, tone = 'active' }: { children: ReactNode; tone?: 'alert' | 'done' | 'muted' | 'active' | 'delay' }) =>
  <span className={`tag ${tone}`}>{children}</span>

export function Eyebrow({ children }: { children: ReactNode }) {
  return <div className="eyebrow label strong">{children}</div>
}

export function PageHead({ eyebrow, title, children }: { eyebrow: string; title: string; children?: ReactNode }) {
  return (
    <div className="page-head">
      <div className="grow stack" style={{ gap: 8 }}>
        <span className="label">{eyebrow}</span>
        <h1 className="display">{title}</h1>
      </div>
      {children}
    </div>
  )
}

export function Kpi({ label, value, to, alert, sub }: { label: string; value: ReactNode; to?: string; alert?: boolean; sub?: string }) {
  const body = (
    <>
      <span className="label">{label}</span>
      <span className="value num">{value}</span>
      {sub && <span className="hint">{sub}</span>}
    </>
  )
  return to ? <Link className={`kpi ${alert ? 'alert' : ''}`} to={to}>{body}</Link> : <div className={`kpi ${alert ? 'alert' : ''}`}>{body}</div>
}

export function KV({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([k, v]) => [<dt key={`${k}-k`}>{k}</dt>, <dd key={`${k}-v`}>{v ?? '—'}</dd>])}
    </dl>
  )
}

export function Panel({ title, actions, children, alert }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; alert?: boolean }) {
  return (
    <section className={`panel ${alert ? 'alert' : ''}`}>
      {(title || actions) && (
        <div className="panel-head">
          <div className="grow">{typeof title === 'string' ? <Eyebrow>{title}</Eyebrow> : title}</div>
          {actions}
        </div>
      )}
      {children}
    </section>
  )
}

export function Docket({ head, right, children, className }: { head: string; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`docket ${className ?? ''}`}>
      <div className="docket-head"><span>{head}</span><span>{right}</span></div>
      {children}
    </section>
  )
}

export function Tabs<T extends string>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={o.value === value} onClick={() => onChange(o.value)}>{o.label}</button>
      ))}
    </div>
  )
}

// ---- States ---------------------------------------------------------------------------------------------------

export function Loading({ rows = 4, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className="stack" aria-busy="true" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton" />)}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="state" role="alert">
      <span className="label red">Couldn't load this</span>
      <span>{errorMessage(error)}</span>
      {onRetry && <Button small onClick={onRetry}>Try again</Button>}
    </div>
  )
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state">
      <span className="label strong">{title}</span>
      {children && <span className="muted">{children}</span>}
    </div>
  )
}

/** Shows loading, error or the content for a query. */
export function QueryView<T>({ q, children, rows }: { q: { isPending: boolean; isError: boolean; error: unknown; data: T | undefined; refetch: () => unknown }; children: (data: T) => ReactNode; rows?: number }) {
  if (q.isPending) return <Loading rows={rows} />
  if (q.isError) return <ErrorState error={q.error} onRetry={() => q.refetch()} />
  return <>{children(q.data as T)}</>
}

// ---- Dialogs --------------------------------------------------------------------------------------------------

export function Dialog({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    ref.current?.querySelector<HTMLElement>('input, textarea, select, button')?.focus()
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div ref={ref} className={`dialog ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="row between"><h2 className="display">{title}</h2><button className="link" onClick={onClose}>Close</button></div>
        {children}
      </div>
    </div>
  )
}

/**
 * Confirms a change that needs a written reason (cancellations, price changes, refunds…). The confirm button stays
 * disabled until the reason is long enough; the server enforces the same minimum.
 */
export function ReasonDialog({
  title, body, label = 'Reason', minLength = 5, confirm, danger, busy, error, onConfirm, onClose, children,
}: {
  title: string; body?: ReactNode; label?: string; minLength?: number; confirm: string; danger?: boolean; busy?: boolean
  error?: string | null; onConfirm: (reason: string) => void; onClose: () => void; children?: ReactNode
}) {
  const [reason, setReason] = useState('')
  const ok = reason.trim().length >= minLength
  return (
    <Dialog title={title} onClose={onClose}>
      {body && <div className="muted">{body}</div>}
      {children}
      <Field label={label} hint={`At least ${minLength} characters. Saved in the audit log.`}>
        {(id) => <textarea id={id} className="textarea" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} />}
      </Field>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="row">
        <Button kind={danger ? 'danger' : 'primary'} disabled={!ok} busy={busy} onClick={() => onConfirm(reason.trim())}>{confirm}</Button>
        <Button kind="ghost" onClick={onClose}>Back</Button>
      </div>
    </Dialog>
  )
}

// ---- Toasts ---------------------------------------------------------------------------------------------------

const ToastContext = createContext<(msg: string, error?: boolean) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ msg: string; error: boolean; key: number } | null>(null)
  const show = useCallback((msg: string, error = false) => setToast({ msg, error, key: Date.now() }), [])
  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 4200)
    return () => clearTimeout(t)
  }, [toast])
  return (
    <ToastContext.Provider value={show}>
      {children}
      {toast && <div key={toast.key} className={`toast ${toast.error ? 'error' : ''}`} role="status" aria-live="polite">{toast.msg}</div>}
    </ToastContext.Provider>
  )
}

export const useToast = () => useContext(ToastContext)

/**
 * A mutation that refreshes [invalidate] on success and reports the outcome in a toast. Errors stay on the mutation
 * too, so dialogs can show them inline.
 */
export function useAction<V, R>(fn: (v: V) => Promise<R>, { invalidate = [], success, onSuccess }: { invalidate?: QueryKey[]; success?: string | ((r: R) => string); onSuccess?: (r: R) => void } = {}) {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: fn,
    onSuccess: (r) => {
      invalidate.forEach((k) => qc.invalidateQueries({ queryKey: k }))
      if (success) toast(typeof success === 'function' ? success(r) : success)
      onSuccess?.(r)
    },
    onError: (e) => toast(errorMessage(e), true),
  })
}
