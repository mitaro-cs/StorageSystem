<script lang="ts">
	import { offline } from '$lib/offline/engine';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Download, ExternalLink, Eye } from '@lucide/svelte';
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
	import Crumbs from '$lib/ui/Crumbs.svelte';
	import { openFiles } from '$lib/files/viewer.svelte';
	import { canPreview, fileKind } from '$lib/fileKinds';

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
	const kind = $derived(m?.file ? fileKind(m.file.mime, m.file.name) : 'other');
	const group = $derived(currentGroup());
	const crumbs = $derived(
		m
			? [
					...(isMulti() && group ? [{ label: group.name }] : []),
					{ label: 'Предметы', href: '/subjects' },
					{ label: m.subjectName, href: `/subjects/${m.subjectId}` },
					{ label: 'Материалы', href: `/subjects/${m.subjectId}?tab=materials` },
					...path.map((c) => ({
						label: c.name,
						href: `/subjects/${m?.subjectId}?tab=materials&folder=${c.id}`
					})),
					{ label: m.title }
				]
			: []
	);
	function view() {
		if (m?.file) openFiles([m.file], 0, m.title);
	}
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
	<Crumbs items={crumbs} />

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
			{#if canPreview(m.file.mime, m.file.name, m.file.size)}
				<Button variant="primary" onclick={view}><Eye size={16} /> Открыть</Button>
				<Button href="{src}?download=true"><Download size={16} /> Скачать</Button>
			{:else}
				<Button variant="primary" href="{src}?download=true"><Download size={16} /> Скачать</Button>
			{/if}
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
			{#if kind === 'pdf'}
				<div class="pdf-inline">
					{#await import('$lib/files/PdfView.svelte') then v}<v.default {src} />{/await}
				</div>
			{:else if kind === 'image'}
				<button class="img-btn" onclick={view} aria-label="Открыть на весь экран"
					><img {src} alt={m.title} /></button
				>
			{:else if mime.startsWith('video/')}
				<!-- svelte-ignore a11y_media_has_caption -->
				<video {src} controls preload="metadata"></video>
			{:else if mime.startsWith('audio/')}
				<audio {src} controls preload="metadata"></audio>
			{:else if kind === 'text'}
				<button class="no-preview open-text" onclick={view}
					><FileIcon {mime} size={22} /> Открыть текст</button
				>
			{:else}
				<p class="faint no-preview">Предпросмотр для этого типа файла недоступен — скачайте его.</p>
			{/if}
		</div>
	{/if}

	<Comments base="/api/materials/{m.id}" canComment={can('comment')} />
{/if}

<style>
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
	.pdf-inline {
		padding: var(--s3);
		background: var(--surface-2);
		max-height: 85dvh;
		overflow-y: auto;
	}
	.img-btn {
		display: block;
		width: 100%;
		padding: 0;
		border: 0;
		background: none;
		cursor: zoom-in;
	}
	.open-text {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 10px;
		width: 100%;
		border: 0;
		background: none;
		font: inherit;
		color: var(--text);
		cursor: pointer;
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
