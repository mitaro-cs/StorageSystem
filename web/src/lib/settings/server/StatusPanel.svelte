<script lang="ts">
	import { FileText, FolderOpen } from '@lucide/svelte';
	import { onMount } from 'svelte';
	import { get, post } from '$lib/api';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { session } from '$lib/session.svelte';
	import { toastError } from '$lib/toasts.svelte';
	import Button from '$lib/ui/Button.svelte';
	import UpdateCard from './UpdateCard.svelte';
	import type { Status } from './types';

	let s = $state<Status | null>(null);

	onMount(() => {
		let alive = true;
		(async () => {
			try {
				s = await get<Status>('/api/admin/status');
			} catch (e) {
				toastError(e);
				return;
			}
			// В окне хоста сервер просит оболочку проверить обновления сейчас — её ответ приходит
			// через пару секунд, и тогда появится кнопка «Обновить сейчас».
			for (const wait of [4000, 12000, 30000]) {
				if (!alive || !session.me?.hostWindow || s?.canUpdate) break;
				await new Promise((r) => setTimeout(r, wait));
				if (!alive) break;
				try {
					s = await get<Status>('/api/admin/status');
				} catch {
					break;
				}
			}
		})();
		return () => {
			alive = false;
		};
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
	<UpdateCard {s} />
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
