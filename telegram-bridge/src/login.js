const fs = require('fs')
const path = require('path')
const { TelegramClient } = require('telegram')
const { StringSession } = require('telegram/sessions')
const readline = require('node:readline/promises')
require('dotenv').config()

const SESSION_FILE = process.env.SESSION_FILE || '/data/session'

// One-time interactive login. Prints TELEGRAM_SESSION to paste into .env.
;(async () => {
  const apiId = parseInt(process.env.API_ID || '0', 10)
  const apiHash = process.env.API_HASH || ''
  if (!apiId || !apiHash) {
    console.error('Set API_ID and API_HASH in .env first (from https://my.telegram.org).')
    process.exit(1)
  }
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  const client = new TelegramClient(new StringSession(''), apiId, apiHash, { connectionRetries: 5 })
  await client.start({
    phoneNumber: async () => (await rl.question('Phone (+55...): ')).trim(),
    password: async () => (await rl.question('2FA password (leave blank if none): ')).trim(),
    phoneCode: async () => (await rl.question('Login code: ')).trim(),
    onError: (err) => console.error(err),
  })
  const s = String(client.session.save())
  try {
    fs.mkdirSync(path.dirname(SESSION_FILE), { recursive: true })
    fs.writeFileSync(SESSION_FILE, s)
    console.log('\nSession saved to ' + SESSION_FILE + ' — now run: docker compose up -d')
  } catch (e) {
    console.error('Could not write session file (' + e.message + '). Put this in .env instead:')
    console.log('TELEGRAM_SESSION=' + s)
  }
  await client.disconnect()
  rl.close()
  process.exit(0)
})()
