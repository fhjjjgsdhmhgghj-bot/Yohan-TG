"""
Telegram Member Adder Engine
Adapted from original Telethon userbot.
- Multi-account support via separate session files
- No admin rights required on target group
- Pure client-side, no server
"""

import asyncio
import json
import os
from telethon import TelegramClient
from telethon.sessions import StringSession
from telethon.tl.functions.channels import InviteToChannelRequest, GetParticipantRequest
from telethon.errors import (
    FloodWaitError,
    UserPrivacyRestrictedError,
    UserAlreadyParticipantError,
    SessionPasswordNeededError,
)

# In-memory state for pending logins (phone_code_hash etc.)
_pending = {}


def _session_path(base_dir: str, name: str) -> str:
    return os.path.join(base_dir, f"session_{name}.txt")


def _load_session(base_dir: str, name: str):
    path = _session_path(base_dir, name)
    if os.path.exists(path):
        with open(path, "r") as f:
            return f.read().strip()
    return None


def _save_session(base_dir: str, name: str, session_string: str):
    path = _session_path(base_dir, name)
    with open(path, "w") as f:
        f.write(session_string)


def send_code(api_id: int, api_hash: str, phone: str, session_name: str, base_dir: str) -> str:
    """Send verification code. Called from Kotlin."""
    async def _run():
        session_str = _load_session(base_dir, session_name)
        client = TelegramClient(
            StringSession(session_str) if session_str else StringSession(),
            api_id,
            api_hash,
        )
        await client.connect()
        if await client.is_user_authorized():
            me = await client.get_me()
            session_string = client.session.save()
            _save_session(base_dir, session_name, session_string)
            await client.disconnect()
            return json.dumps({
                "status": "ok",
                "already": True,
                "name": f"{me.first_name} (@{me.username or ''})",
            })

        sent = await client.send_code_request(phone)
        _pending[session_name] = {
            "client": client,
            "phone": phone,
            "phone_code_hash": sent.phone_code_hash,
            "api_id": api_id,
            "api_hash": api_hash,
            "base_dir": base_dir,
        }
        return json.dumps({"status": "ok"})

    try:
        return asyncio.run(_run())
    except Exception as e:
        return json.dumps({"status": "error", "error": str(e)})


def sign_in(session_name: str, base_dir: str, code: str) -> str:
    """Sign in with the code. May require 2FA."""
    async def _run():
        data = _pending.get(session_name)
        if not data:
            return json.dumps({"status": "error", "error": "no pending login"})

        client = data["client"]
        try:
            await client.sign_in(
                data["phone"],
                code,
                phone_code_hash=data["phone_code_hash"],
            )
        except SessionPasswordNeededError:
            return json.dumps({"status": "2fa"})
        except Exception as e:
            return json.dumps({"status": "error", "error": str(e)})

        session_string = client.session.save()
        _save_session(base_dir, session_name, session_string)
        me = await client.get_me()
        await client.disconnect()
        del _pending[session_name]
        return json.dumps({
            "status": "ok",
            "name": f"{me.first_name} (@{me.username or ''})",
        })

    try:
        return asyncio.run(_run())
    except Exception as e:
        return json.dumps({"status": "error", "error": str(e)})


def check_password(session_name: str, base_dir: str, password: str) -> str:
    """Complete 2FA login."""
    async def _run():
        data = _pending.get(session_name)
        if not data:
            return json.dumps({"status": "error", "error": "no pending login"})

        client = data["client"]
        try:
            await client.sign_in(password=password)
        except Exception as e:
            return json.dumps({"status": "error", "error": str(e)})

        session_string = client.session.save()
        _save_session(base_dir, session_name, session_string)
        me = await client.get_me()
        await client.disconnect()
        del _pending[session_name]
        return json.dumps({
            "status": "ok",
            "name": f"{me.first_name} (@{me.username or ''})",
        })

    try:
        return asyncio.run(_run())
    except Exception as e:
        return json.dumps({"status": "error", "error": str(e)})


def _extract_username(link: str) -> str:
    link = link.strip()
    if "t.me/" in link:
        return link.split("t.me/")[-1].split("/")[0].split("?")[0]
    if "telegram.me/" in link:
        return link.split("telegram.me/")[-1].split("/")[0].split("?")[0]
    return link.replace("@", "")


async def _collect_users(client: TelegramClient, source_entity) -> list:
    """Collect unique user IDs who sent messages in the source group."""
    all_users = {}
    all_messages = []

    for batch_num in range(50):
        try:
            if batch_num == 0:
                messages = await client.get_messages(source_entity, limit=100)
            else:
                last = all_messages[-1] if all_messages else None
                if not last or last.id is None:
                    break
                messages = await client.get_messages(
                    source_entity, limit=100, offset_id=last.id
                )
            if not messages:
                break
            all_messages.extend(messages)
            if len(messages) < 100:
                break
        except FloodWaitError as e:
            await asyncio.sleep(e.seconds)
        except Exception:
            break

    for msg in all_messages:
        if msg.sender_id and msg.sender_id > 0:
            uid = msg.sender_id
            if uid not in all_users:
                all_users[uid] = True

    return list(all_users.keys())


async def _add_member(client: TelegramClient, target_entity, user_id, semaphore):
    async with semaphore:
        try:
            user_entity = await client.get_entity(user_id)
            await client(InviteToChannelRequest(channel=target_entity, users=[user_entity]))
            return True, None
        except FloodWaitError as e:
            await asyncio.sleep(e.seconds)
            return False, "flood"
        except UserPrivacyRestrictedError:
            return False, "privacy"
        except UserAlreadyParticipantError:
            return True, "already"
        except Exception as e:
            return False, str(e)


async def _run_one_account(
    api_id: int,
    api_hash: str,
    session_name: str,
    base_dir: str,
    source_link: str,
    target_link: str,
    concurrency: int,
) -> dict:
    session_str = _load_session(base_dir, session_name)
    if not session_str:
        return {"session": session_name, "error": "no session"}

    client = TelegramClient(StringSession(session_str), api_id, api_hash)
    await client.connect()

    if not await client.is_user_authorized():
        await client.disconnect()
        return {"session": session_name, "error": "not authorized"}

    try:
        source_entity = await client.get_entity(f"@{_extract_username(source_link)}")
        target_entity = await client.get_entity(f"@{_extract_username(target_link)}")
    except Exception as e:
        await client.disconnect()
        return {"session": session_name, "error": f"group access: {e}"}

    # No admin check — as requested
    users = await _collect_users(client, source_entity)
    if not users:
        await client.disconnect()
        return {"session": session_name, "added": 0, "failed": 0, "total": 0}

    semaphore = asyncio.Semaphore(concurrency)
    tasks = [
        asyncio.create_task(_add_member(client, target_entity, uid, semaphore))
        for uid in users
    ]

    added = 0
    failed = 0
    for coro in asyncio.as_completed(tasks):
        ok, _ = await coro
        if ok:
            added += 1
        else:
            failed += 1

    await client.disconnect()
    return {
        "session": session_name,
        "added": added,
        "failed": failed,
        "total": len(users),
    }


def run_adder(
    api_id: int,
    api_hash: str,
    session_names,
    base_dir: str,
    source_link: str,
    target_link: str,
    concurrency: int,
) -> str:
    """
    Main entry: run the adder across all accounts.
    No admin rights required on target.
    """
    async def _run():
        results = []
        logs = []
        total_added = 0
        total_failed = 0

        for name in session_names:
            logs.append(f"[{name}] بدء...")
            res = await _run_one_account(
                api_id, api_hash, name, base_dir,
                source_link, target_link, concurrency,
            )
            results.append(res)
            if "error" in res:
                logs.append(f"[{name}] خطأ: {res['error']}")
            else:
                logs.append(
                    f"[{name}] نجح: {res['added']} | فشل: {res['failed']} | إجمالي: {res['total']}"
                )
                total_added += res.get("added", 0)
                total_failed += res.get("failed", 0)

        return json.dumps({
            "status": "ok",
            "added": total_added,
            "failed": total_failed,
            "log": "\n".join(logs),
            "details": results,
        })

    try:
        return asyncio.run(_run())
    except Exception as e:
        return json.dumps({"status": "error", "error": str(e)})