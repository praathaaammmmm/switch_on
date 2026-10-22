import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { LoginPage } from './Login'
import { meFor, mockApi, renderApp } from '../test/utils'

describe('sign in', () => {
  it('shows the server message for wrong credentials and clears the password', async () => {
    const calls = mockApi(({ path, method }) => {
      if (path === '/me') return { status: 401, body: { error: { code: 'unauthorized', message: 'Sign in to continue.' } } }
      if (path === '/session' && method === 'POST') return { status: 401, body: { error: { code: 'invalid_credentials', message: "That email and password don't match an active account." } } }
    })
    renderApp(<LoginPage />, { route: '/login', path: '/login' })
    await userEvent.type(screen.getByLabelText('Work email'), 'owner@switchon.local')
    await userEvent.type(screen.getByLabelText('Password'), 'not the password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("That email and password don't match an active account.")
    expect(screen.getByLabelText('Password')).toHaveValue('')
    const login = calls.find((c) => c.path === '/session')!
    expect(login.body).toEqual({ email: 'owner@switchon.local', password: 'not the password' })
    expect(login.headers['X-Requested-With']).toBe('switchon-admin')
  })

  it('signs in and leaves the login page', async () => {
    mockApi(({ path, method }) => {
      if (path === '/me') return { status: 401, body: { error: { code: 'unauthorized', message: '' } } }
      if (path === '/session' && method === 'POST') return { body: meFor('OWNER') }
    })
    renderApp(<LoginPage />, { route: '/login', path: '/login' })
    await userEvent.type(screen.getByLabelText('Work email'), 'owner@switchon.local')
    await userEvent.type(screen.getByLabelText('Password'), 'a long enough password')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('elsewhere')).toBeInTheDocument()
  })
})
