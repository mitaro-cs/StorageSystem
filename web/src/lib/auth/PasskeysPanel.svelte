<script lang="ts">
	import { onMount } from 'svelte';
	import { Trash2 } from '@lucide/svelte';
	import { del, get } from '$lib/api';
	import { fmtAgo, fmtDate } from '$lib/format';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';
	import {
		addPasskey,
		deviceName,
		passkeyError,
		passkeysSupported,
		type PasskeyInfo
	} from './passkey';

	// Ключи входа: отпечаток, лицо или PIN-код телефона и ноутбука вместо пароля и кода 2FA.
	let keys = $state<PasskeyInfo[] | null>(null);
	let open = $state(false);
	let password = $state('');
	let name = $state('');
	let error = $state('');
	let busy = $state(false);
	const supported = passkeysSupported();

	onMount(load);

	async function load() {
		try {
			keys = await get<PasskeyInfo[]>('/api/me/passkeys');
		} catch {
			keys = [];
		}
	}

	function start() {
		password = '';
		name = deviceName();
		error = '';
		open = true;
	}

	async function add(e: SubmitEvent) {
		e.preventDefault();
		busy = true;
		error = '';
		try {
			await addPasskey(password, name);
			open = false;
			toast('Ключ добавлен — теперь можно входить без пароля', 'ok');
			await load();
		} catch (err) {
			error = passkeyError(err);
		} finally {
			busy = false;
		}
	}

	async function remove(k: PasskeyInfo) {
		if (!confirm(`Удалить ключ «${k.name}»? Войти им больше не получится.`)) return;
		try {
			await del(`/api/me/passkeys/${k.id}`);
			await load();
		} catch (err) {
			toastError(err);
		}
	}
</script>

{#if keys?.length}
	<ul class="keys">
		{#each keys as k (k.id)}
			<li>
				<span class="txt">
					<strong>{k.name}</strong>
					<span class="faint small"
						>добавлен {fmtDate(k.createdAt)}{k.lastUsedAt
							? ` · вход ${fmtAgo(k.lastUsedAt)}`
							: ''}</span
					>
				</span>
				<button class="icon-btn" onclick={() => remove(k)} aria-label="Удалить ключ {k.name}"
					><Trash2 size={17} /></button
				>
			</li>
		{/each}
	</ul>
{/if}

{#if supported}
	<div class="row wrap">
		<Button variant={keys?.length ? 'secondary' : 'primary'} onclick={start}
			>{keys?.length ? 'Добавить ещё ключ' : 'Добавить ключ'}</Button
		>
	</div>
{:else}
	<p class="hint">
		Добавить ключ можно, открыв сайт группы по его адресу в браузере телефона или компьютера — в
		окне приложения хоста и по IP-адресу ключи не работают.
	</p>
{/if}

<Modal bind:open title="Вход по отпечатку или лицу">
	<form id="passkey-form" class="stack" onsubmit={add}>
		<p class="muted">
			Подтвердите пароль — затем устройство спросит отпечаток, лицо или PIN-код. Ключ хранится
			только на устройстве (или в связке ключей iCloud и Google) — на сервере лишь его открытая
			часть.
		</p>
		<div>
			<label class="label" for="pk-password">Пароль</label>
			<input
				id="pk-password"
				class="input"
				type="password"
				autocomplete="current-password"
				bind:value={password}
				required
			/>
		</div>
		<div>
			<label class="label" for="pk-name">Название ключа</label>
			<input id="pk-name" class="input" maxlength="40" bind:value={name} />
		</div>
		{#if error}<p class="error-text" role="alert">{error}</p>{/if}
	</form>
	{#snippet footer()}
		<Button onclick={() => (open = false)}>Отмена</Button>
		<Button variant="primary" type="submit" form="passkey-form" loading={busy}>Добавить</Button>
	{/snippet}
</Modal>

<style>
	.keys {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 2px;
		border-radius: var(--r-s);
		overflow: hidden;
	}
	.keys li {
		display: flex;
		align-items: center;
		gap: var(--s3);
		padding: 10px 8px 10px 14px;
		background: var(--surface-2);
	}
	.txt {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.icon-btn {
		flex: none;
		display: grid;
		place-items: center;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
	}
	.icon-btn:hover {
		background: var(--surface-3);
		color: var(--danger);
	}
</style>
