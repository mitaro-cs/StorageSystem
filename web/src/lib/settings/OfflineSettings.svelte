<script lang="ts">
	import { onMount } from 'svelte';
	import { CloudOff, RefreshCw, Trash2 } from '@lucide/svelte';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { flushOutbox, offline, pendingLabels, syncNow } from '$lib/offline/engine';
	import {
		filesPolicy,
		forgetFiles,
		prefetchFiles,
		setFilesPolicy,
		usage,
		type FilesPolicy
	} from '$lib/offline/files';
	import { snapshot } from '$lib/offline/engine';
	import Button from '$lib/ui/Button.svelte';

	const CHOICES: { value: FilesPolicy; label: string; hint: string }[] = [
		{ value: 'small', label: 'До 20 МБ', hint: 'обычные конспекты и методички' },
		{ value: 'all', label: 'Все файлы', hint: 'займёт больше места' },
		{ value: 'opened', label: 'Только открытые', hint: 'что открывали хотя бы раз' }
	];

	let policy = $state<FilesPolicy>('small');
	let used = $state<{ files: number; bytes: number | null }>({ files: 0, bytes: null });
	let labels = $state<string[]>([]);
	let busy = $state(false);

	async function refresh() {
		used = await usage();
		labels = await pendingLabels().catch(() => []);
	}

	onMount(() => {
		policy = filesPolicy();
		refresh();
	});

	$effect(() => {
		void offline.version;
		void offline.pending;
		refresh();
	});

	async function choose(p: FilesPolicy) {
		policy = p;
		setFilesPolicy(p);
		if (p !== 'opened') prefetchFiles(await snapshot()).then(refresh);
	}

	async function update() {
		busy = true;
		try {
			await flushOutbox();
			await syncNow();
			await refresh();
		} finally {
			busy = false;
		}
	}

	async function clearFiles() {
		await forgetFiles();
		await refresh();
	}
</script>

<section class="card pane" id="offline">
	<p class="muted">
		Задания, новости, материалы и участники хранятся на этом устройстве и открываются без сети.
		Отметки, комментарии и публикации без сети сохраняются здесь и уходят на сервер, когда интернет
		появится.
	</p>

	<dl class="kv">
		<div>
			<dt>На устройстве</dt>
			<dd class="num">
				{offline.counts.homework} заданий, {offline.counts.news} новостей, {offline.counts
					.materials}
				материалов
			</dd>
		</div>
		<div>
			<dt>Обновлено</dt>
			<dd class="num">{offline.lastSync ? fmtAgo(offline.lastSync) : 'ещё ни разу'}</dd>
		</div>
		<div>
			<dt>Ждёт отправки</dt>
			<dd class="num">{offline.pending || 'ничего'}</dd>
		</div>
	</dl>
	{#if labels.length}
		<ul class="queue">
			{#each labels as l, i (i)}<li><CloudOff size={14} /> {l}</li>{/each}
		</ul>
	{/if}
	<div>
		<Button onclick={update} loading={busy || offline.syncing}
			><RefreshCw size={16} /> Обновить сейчас</Button
		>
	</div>
</section>

<section class="card pane">
	<div>
		<h3>Файлы материалов</h3>
		<p class="muted small">Какие файлы держать на устройстве, чтобы они открывались без сети.</p>
	</div>
	<div class="choices" role="radiogroup" aria-label="Какие файлы хранить на устройстве">
		{#each CHOICES as c (c.value)}
			<button
				role="radio"
				aria-checked={policy === c.value}
				class:on={policy === c.value}
				onclick={() => choose(c.value)}
			>
				<strong>{c.label}</strong>
				<span class="small">{c.hint}</span>
			</button>
		{/each}
	</div>
	<dl class="kv">
		<div>
			<dt>Сохранено файлов</dt>
			<dd class="num">{used.files}</dd>
		</div>
		{#if used.bytes !== null}
			<div>
				<dt>Занято на устройстве</dt>
				<dd class="num">{fmtSize(used.bytes)}</dd>
			</div>
		{/if}
	</dl>
	{#if used.files}
		<div>
			<Button onclick={clearFiles}><Trash2 size={16} /> Удалить файлы с устройства</Button>
		</div>
	{/if}
</section>

<style>
	h3 {
		font-size: 17px;
	}
	h3 + p {
		margin-top: 2px;
	}
	.queue {
		list-style: none;
		margin: 0;
		padding: 10px 14px;
		border-radius: var(--r);
		background: var(--amber-soft);
		color: var(--amber);
		font-size: 14px;
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.queue li {
		display: flex;
		align-items: center;
		gap: 8px;
	}
	/* По ширине карточки, а не экрана: в настройках рядом меню разделов. */
	.choices {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
		gap: 8px;
	}
	.choices button {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 2px;
		padding: 12px 14px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		text-align: left;
	}
	.choices button span {
		color: var(--text-2);
	}
	.choices button.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
	.choices button.on span {
		color: inherit;
		opacity: 0.85;
	}
</style>
