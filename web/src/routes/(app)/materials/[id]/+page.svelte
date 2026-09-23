<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ChevronRight, Download, ExternalLink } from '@lucide/svelte';
	import { get } from '$lib/api';
	import { track } from '$lib/recent';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { can, currentGroup, isMulti } from '$lib/session.svelte';
	import type { Material, MaterialListing } from '$lib/types';
	import { materialActions } from '$lib/content/materialActions';
	import Author from '$lib/content/Author.svelte';
	import Comments from '$lib/content/Comments.svelte';
	import FileIcon from '$lib/content/FileIcon.svelte';
	import BackBar from '$lib/ui/BackBar.svelte';
	import Button from '$lib/ui/Button.svelte';
	import Menu from '$lib/ui/Menu.svelte';
	import Skeleton from '$lib/ui/Skeleton.svelte';
	import Empty from '$lib/ui/Empty.svelte';

	let m = $state<Material | null>(null);
	let path = $state<{ id: number; name: string }[]>([]);
	let missing = $state(false);

	async function load() {
		try {
			m = await get<Material>(`/api/materials/${page.params.id}`);
			track({ type: 'material', id: m.id, title: m.title, color: m.subjectColor });
			if (m.folderId) {
				const l = await get<MaterialListing>(
					`/api/subjects/${m.subjectId}/materials?folder=${m.folderId}`
				);
				path = l.path;
			} else path = [];
		} catch {
			missing = true;
		}
	}

	$effect(() => {
		void page.params.id;
		void offline.version;
		load();
	});

	const mime = $derived(m?.file?.mime ?? '');
	const src = $derived(m?.file ? `/api/files/${m.file.id}` : '');
	const group = $derived(currentGroup());
</script>

<svelte:head><title>{m?.title ?? 'Материал'} · groupbase</title></svelte:head>

<BackBar
	href={m ? `/subjects/${m.subjectId}?tab=materials` : '/materials'}
	label={m?.subjectName ?? 'Материалы'}
/>

{#if missing}
	<div class="card">
		<Empty title="Материал не найден" text="Его удалили или он недоступен вашей группе." />
	</div>
{:else if !m}
	<Skeleton lines={5} />
{:else}
	<nav class="crumbs small" aria-label="Путь">
		{#if isMulti() && group}<span>{group.name}</span><ChevronRight size={13} />{/if}
		<a href="/subjects/{m.subjectId}?tab=materials">{m.subjectName}</a>
		{#each path as c (c.id)}
			<ChevronRight size={13} /><a href="/subjects/{m.subjectId}?tab=materials&folder={c.id}"
				>{c.name}</a
			>
		{/each}
		<ChevronRight size={13} /><span aria-current="page">{m.title}</span>
	</nav>

	<article class="card head">
		<FileIcon mime={m.file?.mime} link={m.kind === 'link'} size={24} />
		<div class="info">
			<h1>{m.title}</h1>
			<p class="faint small num">
				{#if m.file}{fmtSize(m.file.size)} ·
				{/if}<Author person={m.author} size={18} /> · {fmtAgo(m.createdAt)}
			</p>
		</div>
		{#if m.file}
			<Button variant="primary" href="{src}?download=true"><Download size={16} /> Скачать</Button>
		{:else if m.url}
			<a class="btn-link" href={m.url} target="_blank" rel="noopener noreferrer nofollow"
				><ExternalLink size={16} /> Открыть</a
			>
		{/if}
		<Menu
			items={materialActions(m, () =>
				m?.status === 'published' ? load() : goto(`/subjects/${m?.subjectId}?tab=materials`)
			)}
		/>
	</article>

	{#if m.status === 'pending'}<p class="chip amber pending">Ждёт проверки старостой</p>{/if}
	{#if m.description}<p class="desc">{m.description}</p>{/if}

	{#if m.file}
		<div class="preview">
			{#if mime === 'application/pdf'}
				<iframe {src} title="Предпросмотр: {m.title}" class="pdf"></iframe>
				<a class="open-pdf" href={src} target="_blank" rel="noopener">
					<FileIcon {mime} size={22} />
					<span
						><strong>Открыть PDF</strong><span class="faint small">в просмотрщике телефона</span
						></span
					>
				</a>
			{:else if mime.startsWith('image/') && !mime.includes('svg')}
				<img {src} alt={m.title} />
			{:else if mime.startsWith('video/')}
				<!-- svelte-ignore a11y_media_has_caption -->
				<video {src} controls preload="metadata"></video>
			{:else if mime.startsWith('audio/')}
				<audio {src} controls preload="metadata"></audio>
			{:else if mime === 'text/plain'}
				<iframe {src} title="Предпросмотр: {m.title}" class="text"></iframe>
			{:else}
				<p class="faint no-preview">Предпросмотр для этого типа файла недоступен — скачайте его.</p>
			{/if}
		</div>
	{/if}

	<Comments base="/api/materials/{m.id}" canComment={can('comment')} />
{/if}

<style>
	.crumbs {
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
		color: var(--text-3);
		margin-bottom: var(--s3);
	}
	.crumbs a {
		color: var(--text-2);
	}
	.head {
		display: flex;
		align-items: center;
		gap: var(--s3);
		flex-wrap: wrap;
	}
	.info {
		flex: 1;
		min-width: 200px;
	}
	.info h1 {
		font-size: 20px;
		overflow-wrap: anywhere;
	}
	.info p {
		display: flex;
		align-items: center;
		gap: 4px;
		margin-top: 4px;
		flex-wrap: wrap;
	}
	.btn-link {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		height: 40px;
		padding: 0 16px;
		border-radius: var(--r-s);
		background: var(--accent);
		color: var(--accent-text);
		font-weight: 560;
	}
	.btn-link:hover {
		text-decoration: none;
		background: var(--accent-hover);
	}
	.pending {
		margin-top: var(--s3);
		display: inline-flex;
	}
	.desc {
		margin-top: var(--s3);
		white-space: pre-wrap;
		color: var(--text-2);
	}
	.preview {
		margin-top: var(--s4);
		border-radius: var(--r-l);
		overflow: hidden;
		background: var(--surface);
		box-shadow: var(--shadow-1);
	}
	iframe {
		display: block;
		width: 100%;
		height: min(80dvh, 1000px);
		border: 0;
		background: #fff;
	}
	iframe.text {
		height: 60dvh;
	}
	.open-pdf {
		display: none;
		align-items: center;
		gap: 12px;
		padding: var(--s4);
		color: var(--text);
	}
	.open-pdf span {
		display: flex;
		flex-direction: column;
	}
	.open-pdf:hover {
		text-decoration: none;
	}
	/* На телефонах PDF в iframe обычно не показывается — открываем системным просмотрщиком. */
	@media (max-width: 700px), (pointer: coarse) {
		iframe.pdf {
			display: none;
		}
		.open-pdf {
			display: flex;
		}
	}
	img,
	video {
		display: block;
		max-width: 100%;
		margin: 0 auto;
		max-height: 80dvh;
	}
	audio {
		width: 100%;
		padding: var(--s3);
	}
	.no-preview {
		padding: var(--s6) var(--s4);
		text-align: center;
	}
</style>
