<script lang="ts">
	import { Cloud, Download, FolderOpen, HardDrive, History, Upload } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { get, post, put } from '$lib/api';
	import { putFile } from '$lib/upload';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { session } from '$lib/session.svelte';
	import { toast, toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import { waitForRestart } from './restart';
	import type { BackupInfo, Backups } from './types';

	let b = $state<Backups | null>(null);
	let busy = $state(false);
	let restarting = $state(false);
	let file: HTMLInputElement | undefined = $state();

	async function load() {
		try {
			b = await get<Backups>('/api/admin/backups');
		} catch (e) {
			toastError(e);
		}
	}
	onMount(load);

	async function createNow() {
		busy = true;
		try {
			await post('/api/admin/backups', {});
			toast('Копия сохранена', 'ok');
			await load();
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
		}
	}

	async function choose(cloud: string | null) {
		try {
			b = await put<Backups>('/api/admin/backups/settings', { cloud });
			toast(
				cloud ? 'Копии будут сохраняться в облачную папку' : 'Копии — на этом компьютере',
				'ok'
			);
		} catch (e) {
			toastError(e);
		}
	}

	async function openFolder() {
		try {
			await post('/api/desktop/open', { what: 'backups' });
		} catch (e) {
			toastError(e);
		}
	}

	async function afterRestore(r: { status: string; message: string }) {
		if (r.status === 'restarting') {
			restarting = true;
			await waitForRestart();
		} else {
			toast(r.message, 'info');
		}
	}

	async function restore(item: BackupInfo) {
		const when = new Date(item.createdAt).toLocaleString('ru-RU');
		if (
			!window.confirm(
				`Вернуть все данные к копии от ${when}? Всё, что появилось после неё, будет отложено в сторону (не удалено).`
			)
		)
			return;
		try {
			await afterRestore(
				await post<{ status: string; message: string }>(
					`/api/admin/backups/${encodeURIComponent(item.name)}/restore`,
					{}
				)
			);
		} catch (e) {
			toastError(e);
		}
	}

	async function restoreFile(f: File) {
		if (
			!window.confirm(
				`Восстановить данные из файла «${f.name}»? Текущие данные будут отложены в сторону (не удалены).`
			)
		)
			return;
		busy = true;
		try {
			await afterRestore(
				await putFile<{ status: string; message: string }>('/api/admin/backups/restore', f)
			);
		} catch (e) {
			toastError(e);
		} finally {
			busy = false;
			if (file) file.value = '';
		}
	}
</script>

{#if restarting}
	<section class="card restarting" role="status">
		<span class="spinner" aria-hidden="true"></span>
		<div>
			<strong>Восстанавливаем данные…</strong>
			<p class="small muted">Сервер перезапускается — страница обновится сама.</p>
		</div>
	</section>
{:else if b}
	<section class="card backups">
		<div class="state">
			<History size={20} />
			<div>
				{#if b.status.lastOkAt}
					<strong>Последняя копия — {fmtAgo(b.status.lastOkAt)}</strong>
				{:else}
					<strong>Копий ещё не было</strong>
				{/if}
				<p class="small muted">
					{b.enabled
						? `Копия делается сама раз в сутки, хранятся последние ${b.keep}.`
						: 'Автоматические копии выключены в настройках сервера.'}
				</p>
				{#if b.status.lastError}
					<p class="small bad">Последняя попытка не удалась: {b.status.lastError}</p>
				{/if}
			</div>
		</div>
		<div class="row wrap">
			<Button variant="primary" loading={busy} onclick={createNow}>Сделать копию сейчас</Button>
			{#if session.me?.hostWindow}
				<Button variant="ghost" onclick={openFolder}><FolderOpen size={16} /> Открыть папку</Button>
			{/if}
		</div>
	</section>

	<h3 class="sub">Где хранить копии</h3>
	<div class="choices" role="radiogroup" aria-label="Где хранить копии">
		<button role="radio" aria-checked={!b.cloud} class:on={!b.cloud} onclick={() => choose(null)}>
			<span class="head"><HardDrive size={18} /> <strong>На этом компьютере</strong></span>
			<span class="small">Если с компьютером что-то случится, копии пропадут вместе с ним</span>
		</button>
		{#each b.choices as c (c.path)}
			<button
				role="radio"
				aria-checked={b.cloud?.path === c.path}
				class:on={b.cloud?.path === c.path}
				onclick={() => choose(c.path)}
			>
				<span class="head"><Cloud size={18} /> <strong>{c.label}</strong></span>
				<span class="small">Копии сами уедут в облако — данные переживут поломку компьютера</span>
			</button>
		{/each}
	</div>
	{#if b.choices.length === 0 && session.me?.instance.desktop}
		<p class="faint small">
			Установите Яндекс Диск, iCloud Drive или OneDrive на этот компьютер — и здесь появится выбор
			облачной папки.
		</p>
	{/if}
	<p class="tip amber small">
		<span
			>В копии все данные группы и ключи шифрования файлов. Храните её только в своём личном облаке
			и не пересылайте.</span
		>
	</p>

	{#if b.items.length}
		<h3 class="sub">Копии</h3>
		<div class="list">
			{#each b.items as item (item.name)}
				<div class="list-row">
					<div class="info">
						<strong class="num">{new Date(item.createdAt).toLocaleString('ru-RU')}</strong>
						<span class="faint small num">{fmtSize(item.size)}</span>
					</div>
					<Button
						size="s"
						variant="ghost"
						href={`/api/admin/backups/${encodeURIComponent(item.name)}`}
						label="Скачать копию"><Download size={15} /></Button
					>
					<Button size="s" onclick={() => restore(item)}>Восстановить</Button>
				</div>
			{/each}
		</div>
	{/if}

	<h3 class="sub">Перенос на другой компьютер</h3>
	<p class="small muted">
		Скачайте копию, установите groupbase на новом компьютере и при первом запуске выберите
		«Восстановить из копии». Или загрузите копию сюда, чтобы вернуть данные на этом компьютере.
	</p>
	<div>
		<input
			bind:this={file}
			type="file"
			accept=".zip,application/zip"
			class="sr-only"
			id="restore-file"
			onchange={(e) => e.currentTarget.files?.[0] && restoreFile(e.currentTarget.files[0])}
		/>
		<Button onclick={() => file?.click()} loading={busy}
			><Upload size={16} /> Восстановить из файла…</Button
		>
	</div>
{/if}

<style>
	.backups {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
	}
	.state {
		display: flex;
		align-items: flex-start;
		gap: 12px;
	}
	.state strong {
		font-size: 17px;
	}
	.bad {
		color: var(--danger);
	}
	.sub {
		margin: var(--s5) 0 var(--s3);
	}
	.choices {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 8px;
		margin-bottom: var(--s3);
	}
	.choices button {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 4px;
		padding: 14px 16px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		text-align: left;
	}
	.choices .head {
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.choices button > .small {
		color: var(--text-3);
	}
	.choices button.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.choices button.on > .small {
		color: inherit;
		opacity: 0.75;
	}
	.info {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.restarting {
		display: flex;
		align-items: center;
		gap: var(--s3);
	}
	.spinner {
		flex: none;
		width: 22px;
		height: 22px;
		border: 3px solid var(--border);
		border-top-color: var(--text);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}
	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
	@media (max-width: 520px) {
		.choices {
			grid-template-columns: 1fr;
		}
	}
</style>
