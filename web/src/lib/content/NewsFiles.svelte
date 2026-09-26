<script lang="ts">
	import { fmtSize } from '$lib/format';
	import { openFiles } from '$lib/files/viewer.svelte';
	import type { FileInfo } from '$lib/types';
	import FileIcon from './FileIcon.svelte';

	// Фото и файлы новости: фото — плиткой (нажали — просмотр с листанием), остальное — строками.
	// На главной у карточки только число файлов (NewsCard).
	let { files, title }: { files: FileInfo[]; title: string } = $props();

	const photos = $derived(files.filter((f) => f.mime?.startsWith('image/') && f.id > 0));
	const others = $derived(files.filter((f) => !photos.includes(f)));
	const shown = $derived(photos.slice(0, 4));
	const open = (f: FileInfo) => openFiles(files, files.indexOf(f), title);
</script>

<div class="files">
	{#if shown.length}
		<div class="photos n{shown.length}">
			{#each shown as f, i (f.id)}
				<button type="button" class="photo" onclick={() => open(f)} aria-label="Открыть {f.name}">
					<img src="/api/files/{f.id}" alt="" loading="lazy" decoding="async" />
					{#if i === shown.length - 1 && photos.length > shown.length}
						<span class="more num">+{photos.length - shown.length}</span>
					{/if}
				</button>
			{/each}
		</div>
	{/if}
	{#each others as f (f.id)}
		<button type="button" class="file" onclick={() => open(f)} aria-label="Открыть {f.name}">
			<FileIcon mime={f.mime} size={20} />
			<span class="name">{f.name}</span>
			<span class="faint small num">{fmtSize(f.size)}</span>
		</button>
	{/each}
</div>

<style>
	/* Над ссылкой-растяжкой карточки: нажатие открывает файл, а не новость. */
	.files {
		position: relative;
		z-index: 2;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.photos {
		display: grid;
		gap: 4px;
		border-radius: var(--r);
		overflow: hidden;
	}
	.photos.n1 {
		grid-template-columns: 1fr;
	}
	.photos.n2 {
		grid-template-columns: 1fr 1fr;
	}
	.photos.n3 {
		grid-template-columns: 2fr 1fr;
		grid-template-rows: 1fr 1fr;
	}
	.photos.n3 .photo:first-child {
		grid-row: span 2;
	}
	.photos.n4 {
		grid-template-columns: 1fr 1fr;
	}
	.photo {
		position: relative;
		display: block;
		min-height: 0;
		padding: 0;
		border: 0;
		background: var(--surface-2);
		aspect-ratio: 4 / 3;
		overflow: hidden;
	}
	.n1 .photo {
		aspect-ratio: 16 / 10;
		max-height: 360px;
		width: 100%;
	}
	.n3 .photo:first-child {
		aspect-ratio: auto;
	}
	.photo img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		transition: transform 300ms var(--ease);
	}
	.photo:hover img {
		transform: scale(1.03);
	}
	.more {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		background: rgb(0 0 0 / 0.45);
		color: #fff;
		font-size: 22px;
		font-weight: 700;
	}
	.file {
		display: flex;
		align-items: center;
		gap: 10px;
		min-height: 48px;
		padding: 8px 14px;
		border: 1px solid var(--border);
		border-radius: var(--r);
		background: var(--surface);
		color: var(--text);
		font: inherit;
		text-align: left;
	}
	.file:hover {
		background: var(--surface-2);
	}
	.name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-weight: 550;
	}
</style>
