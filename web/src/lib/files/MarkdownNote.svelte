<script lang="ts">
	import { FileDown } from '@lucide/svelte';
	import { get } from '$lib/api';

	// Конспект в Markdown — страницей (0.9.3): HTML собирает и чистит сервер (content/Markdown),
	// «В Word» — сервер собирает .docx (files/MarkdownDocx).
	let { fileId, dark = false }: { fileId: number; dark?: boolean } = $props();

	let html = $state<string | null>(null);
	let error = $state('');

	$effect(() => {
		const id = fileId;
		html = null;
		error = '';
		get<{ html: string }>(`/api/files/${id}/html`)
			.then((r) => {
				if (id === fileId) html = r.html;
			})
			.catch((e) => (error = e instanceof Error ? e.message : 'Не удалось открыть'));
	});
</script>

<div class="note" class:dark>
	<div class="bar">
		<a class="btn btn-s" href="/api/files/{fileId}/docx" download
			><FileDown size={15} /> Скачать в Word</a
		>
	</div>
	{#if error}<p class="faint">{error}</p>
	{:else if html === null}<p class="faint">Открываем конспект…</p>
	{:else}
		<!-- HTML уже очищен сервером (OWASP-санитайзер): только разметка текста. -->
		<!-- eslint-disable-next-line svelte/no-at-html-tags -->
		<article class="prose">{@html html}</article>
	{/if}
</div>

<style>
	.note {
		display: flex;
		flex-direction: column;
		gap: var(--s3);
		width: 100%;
	}
	.bar {
		display: flex;
		justify-content: flex-end;
	}
	.btn {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 6px 12px;
		border: 1px solid var(--border);
		border-radius: var(--r-full);
		background: var(--surface);
		color: var(--text);
		font-size: 14px;
		font-weight: 600;
		text-decoration: none;
	}
	.prose {
		padding: var(--s5);
		border-radius: var(--r-lg, 16px);
		background: var(--surface);
		box-shadow: inset 0 0 0 1px var(--border);
		overflow-wrap: anywhere;
	}
	.prose :global(table) {
		display: block;
		max-width: 100%;
		overflow-x: auto;
		border-collapse: collapse;
	}
	.prose :global(th),
	.prose :global(td) {
		padding: 6px 10px;
		border: 1px solid var(--border);
	}
	.prose :global(pre) {
		overflow-x: auto;
	}
	/* В окне просмотра файлов фон тёмный — лист как бумага. */
	.dark .prose {
		background: #fff;
		color: #111;
		width: min(100%, 860px);
		margin: 0 auto;
	}
	.dark .bar {
		width: min(100%, 860px);
		margin: 0 auto;
	}
</style>
