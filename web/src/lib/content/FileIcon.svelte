<script lang="ts">
	import {
		File,
		FileArchive,
		FileAudio,
		FileImage,
		FileSpreadsheet,
		FileText,
		FileVideo,
		Link,
		Presentation
	} from '@lucide/svelte';

	let {
		mime = null,
		link = false,
		size = 20
	}: { mime?: string | null; link?: boolean; size?: number } = $props();

	const kind = $derived.by(() => {
		if (link) return 'link';
		const m = mime ?? '';
		if (m.startsWith('image/')) return 'image';
		if (m.startsWith('video/')) return 'video';
		if (m.startsWith('audio/')) return 'audio';
		if (/zip|rar|7z|tar|gzip/.test(m)) return 'archive';
		if (/sheet|excel|csv/.test(m)) return 'sheet';
		if (/presentation|powerpoint/.test(m)) return 'slides';
		if (/pdf|word|text|document|rtf|opendocument/.test(m)) return 'text';
		return 'file';
	});
</script>

<span class="icon {kind}" style:width="{size + 16}px" style:height="{size + 16}px">
	{#if kind === 'link'}<Link {size} />
	{:else if kind === 'image'}<FileImage {size} />
	{:else if kind === 'video'}<FileVideo {size} />
	{:else if kind === 'audio'}<FileAudio {size} />
	{:else if kind === 'archive'}<FileArchive {size} />
	{:else if kind === 'sheet'}<FileSpreadsheet {size} />
	{:else if kind === 'slides'}<Presentation {size} />
	{:else if kind === 'text'}<FileText {size} />
	{:else}<File {size} />{/if}
</span>

<style>
	.icon {
		flex: none;
		display: grid;
		place-items: center;
		border-radius: 10px;
		background: var(--surface-2);
		color: var(--text-2);
	}
	.text {
		background: var(--accent-soft);
		color: var(--accent);
	}
	.link,
	.image {
		background: var(--ok-soft);
		color: var(--ok);
	}
	.sheet {
		background: var(--ok-soft);
		color: var(--ok);
	}
	.slides,
	.video,
	.audio {
		background: var(--amber-soft);
		color: var(--amber);
	}
</style>
