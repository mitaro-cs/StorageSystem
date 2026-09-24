<script lang="ts">
	import { ArrowUpCircle, FileText, FolderOpen } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { get, post } from '$lib/api';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import type { Status } from './types';

	let s = $state<Status | null>(null);
	let updating = $state(false);

	async function updateNow() {
		updating = true;
		try {
			// Дальше всё делает приложение: скачает, остановит сервер, установит и откроется заново.
			await post('/api/desktop/update', {});
		} catch (e) {
			updating = false;
			toastError(e);
		}
	}

	onMount(async () => {
		try {
			s = await get<Status>('/api/admin/status');
		} catch (e) {
			toastError(e);
		}
	});

	async function open(what: 'data' | 'logs') {
		try {
			await post('/api/desktop/open', { what });
		} catch (e) {
			toastError(e);
		}
	}

	// Меньше 2 ГБ свободного места — пора чистить диск: копии и файлы перестанут помещаться.
	const lowDisk = $derived(!!s && s.sizes.free > 0 && s.sizes.free < 2 * 1024 ** 3);
</script>

{#if s}
	{#if s.update && s.canUpdate}
		<div class="card update">
			<ArrowUpCircle size={22} />
			<span>
				<strong>Доступна новая версия {s.update.version}</strong>
				<span class="small muted"
					>Обновится само: сайт будет недоступен около минуты, данные сохранятся.</span
				>
			</span>
			<Button variant="primary" size="s" loading={updating} onclick={updateNow}
				>Обновить сейчас</Button
			>
		</div>
	{:else if s.update}
		<a class="card update" href={s.update.url} target="_blank" rel="noreferrer">
			<ArrowUpCircle size={22} />
			<span>
				<strong>Доступна новая версия {s.update.version}</strong>
				<span class="small muted">Скачайте установщик со страницы выпуска — данные сохранятся.</span
				>
			</span>
		</a>
	{/if}
	<section class="card">
		<dl class="kv">
			<div>
				<dt>Версия</dt>
				<dd class="num">{s.version}</dd>
			</div>
			<div>
				<dt>Запущен</dt>
				<dd>{fmtAgo(s.startedAt)}</dd>
			</div>
			<div>
				<dt>База данных</dt>
				<dd class="num">{fmtSize(s.sizes.database)}</dd>
			</div>
			<div>
				<dt>Файлы и аватары</dt>
				<dd class="num">{fmtSize(s.sizes.files)}</dd>
			</div>
			<div>
				<dt>Свободно на диске</dt>
				<dd class="num" class:bad={lowDisk}>{fmtSize(s.sizes.free)}</dd>
			</div>
			{#if s.dataDir}
				<div>
					<dt>Данные</dt>
					<dd class="path" title={s.dataDir}>{s.dataDir}</dd>
				</div>
			{/if}
		</dl>
		{#if lowDisk}
			<p class="tip amber small">
				<span>Места на диске мало — освободите его, иначе новые файлы и копии не сохранятся.</span>
			</p>
		{/if}
		{#if session.me?.hostWindow}
			<div class="row wrap">
				<Button size="s" variant="ghost" onclick={() => open('data')}
					><FolderOpen size={15} /> Папка с данными</Button
				>
				<Button size="s" variant="ghost" onclick={() => open('logs')}
					><FileText size={15} /> Журналы</Button
				>
			</div>
		{/if}
	</section>
{/if}

<style>
	.update {
		display: flex;
		align-items: center;
		gap: 12px;
		margin-bottom: var(--s3);
		color: var(--text);
		text-decoration: none;
		box-shadow: 0 0 0 2px var(--ok);
	}
	.update > span {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.bad {
		color: var(--danger);
	}
	.path {
		font-weight: 500;
		font-size: 13px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 60%;
		direction: rtl;
	}
	section .row {
		margin-top: var(--s2);
	}
</style>
