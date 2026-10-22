import { useState, type FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { AdminMe } from '../api/types'
import { ME_KEY } from '../auth/AuthContext'
import { Button, Field } from '../components/ui'

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const qc = useQueryClient()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setError(null)
    try {
      const me = await api<AdminMe>('/session', { method: 'POST', body: { email: email.trim(), password } })
      qc.setQueryData(ME_KEY, me)
      navigate(from, { replace: true })
    } catch (err) {
      setError(errorMessage(err))
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <section className="login-art">
        <div className="row"><span className="brand-mark" aria-hidden /><span className="display" style={{ fontSize: 26 }}>Switch On</span></div>
        <div className="stack">
          <span className="label">Operations console</span>
          <h1 className="display">Know the fault.<br /><span className="red">Know the price.</span></h1>
        </div>
        <span className="label">Staff only · every change is audited</span>
      </section>
      <form className="login-form" onSubmit={submit} noValidate>
        <h2 className="display">Sign in</h2>
        <Field label="Work email">{(id) => <input id={id} className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />}</Field>
        <Field label="Password">{(id) => <input id={id} className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />}</Field>
        {error && <p className="error-text" role="alert">{error}</p>}
        <Button kind="primary" type="submit" busy={busy} disabled={!email || !password}>Sign in</Button>
        <p className="hint">Accounts are created by an owner in Team &amp; Access. Sessions end after 12 hours.</p>
      </form>
    </div>
  )
}
