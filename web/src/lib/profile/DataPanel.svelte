<script lang="ts">
	import { goto } from '$app/navigation';
	import { Database, Download, LogOut, UserX } from '@lucide/svelte';
	import { ApiError, del, request } from '$lib/api';
	import { forgetAccount } from '$lib/accounts';
	import { clearCache } from '$lib/cache';
	import { offline, wipeOffline } from '$lib/offline/engine';
	import { forgetOfflineData } from '$lib/pwa.svelte';
	import { clearRecent } from '$lib/recent';
	import { session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import { ask } from '$lib/ui/ask.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// Мои данные: скачать всё своё, выйти, удалить аккаунт.
	const me = $derived(session.me!);
	let deleteOpen = $state(false);
	let deletePassword = $state('');

	/** Всё, что хранилось на устройстве, стирается: копия данных, файлы, недавнее. */
	async function forgetDevice() {
		forgetOfflineData();
		await wipeOffline();
		clearCache();
		clearRecent();
	}

	/** Сначала уходим со страницы профиля, потом забываем пользователя — иначе она упадёт. */
	async function leave() {
		await goto('/login', { replaceState: true });
		session.me = null;
	}

	async function logout() {
		if (
			offline.pending > 0 &&
			!(await ask(
				`Без сети сделано действий: ${offline.pending}. Они ещё не отправлены и пропадут. Выйти?`,
				{ title: 'Выйти из аккаунта', ok: 'Выйти', danger: true }
			))
		)
			return;
		try {
			await request('/api/auth/logout', { method: 'POST', body: {} });
		} catch (err) {
			return toastError(
				err instanceof ApiError && err.status === 0
					? new Error('Выйти можно, когда появится интернет')
					: err
			);
		}
		await forgetDevice();
		await leave();
	}

	async function deleteAccount() {
		try {
			await del('/api/me', { password: deletePassword });
			forgetAccount(me.user.id);
			await forgetDevice();
			await leave();
		} catch (err) {
			toastError(err);
		}
	}
</script>

<section class="card pane">
	<div class="pane-title">
		<Database size={18} />
		<h3>Мои данные</h3>
	</div>
	<p class="muted small">
		ZIP-файл со всем, что связано с вами: профиль, отметки «сделано», комментарии, ваши публикации и
		загруженные файлы.
	</p>
	<div>
		<a class="download" href="/api/me/export" download><Download size={17} /> Скачать мои данные</a>
	</div>
</section>

<section class="card pane">
	<div class="pane-title">
		<LogOut size={18} />
		<h3>Выйти из аккаунта</h3>
	</div>
	<p class="muted small">На этом устройстве. Сохранённое для работы без сети сотрётся.</p>
	<div><Button onclick={logout}><LogOut size={16} /> Выйти</Button></div>
</section>

<section class="card pane danger-zone">
	<div class="pane-title">
		<UserX size={18} />
		<h3>Удалить аккаунт</h3>
	</div>
	<p class="muted small">
		Имя, логин, пароль и фото будут стёрты навсегда. Ваши новости и задания останутся с подписью
		«удалённый пользователь».
	</p>
	<div>
		<Button variant="danger" onclick={() => ((deletePassword = ''), (deleteOpen = true))}
			>Удалить аккаунт</Button
		>
	</div>
</section>

<Modal bind:open={deleteOpen} title="Удалить аккаунт">
	<div class="stack">
		<p>
			Имя, логин, пароль и фото будут стёрты. Ваши новости и задания останутся с подписью «удалённый
			пользователь».
		</p>
		<label class="label" for="del-pass">Пароль для подтверждения</label>
		<input
			id="del-pass"
			class="input"
			type="password"
			autocomplete="current-password"
			bind:value={deletePassword}
		/>
	</div>
	{#snippet footer()}
		<Button onclick={() => (deleteOpen = false)}>Отмена</Button>
		<Button variant="danger" onclick={deleteAccount} disabled={!deletePassword}
			>Удалить навсегда</Button
		>
	{/snippet}
</Modal>

<style>
	.danger-zone .pane-title {
		color: var(--danger);
	}
	.download {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 44px;
		padding: 0 20px;
		border: 1px solid var(--border-strong);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font-weight: 580;
	}
	.download:hover {
		background: var(--surface-2);
		text-decoration: none;
	}
</style>
