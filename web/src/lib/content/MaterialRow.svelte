<script lang="ts">
	import { CloudOff, ExternalLink, Eye } from '@lucide/svelte';
	import { openFiles } from '$lib/files/viewer.svelte';
	import { canPreview } from '$lib/fileKinds';
	import { fmtAgo, fmtSize } from '$lib/format';
	import { t } from '$lib/i18n/ru';
	import type { Material } from '$lib/types';
	import FileIcon from './FileIcon.svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';

	let {
		m,
		actions = [],
		showSubject = false
	}: { m: Material; actions?: MenuItem[]; showSubject?: boolean } = $props();
</script>

<div class="list-row mrow" class:dim={m.hidden}>
	<FileIcon mime={m.file?.mime} link={m.kind === 'link'} />
	<div class="main">
		<a class="title" href="/materials/{m.id}">{m.title}</a>
		<span class="faint small meta num">
			{#if showSubject}<SubjectGlyph
					id={m.subjectId}
					name={m.subjectName}
					color={m.subjectColor}
					size={13}
					bare
				/>{m.subjectName} ·{/if}
			{#if m.file}{fmtSize(m.file.size)} ·{:else if m.url}{new URL(m.url).host} ·{/if}
			{m.author.deleted ? t.common.deletedUser : m.author.displayName} · {fmtAgo(m.createdAt)}
		</span>
	</div>
	{#if m.status === 'pending'}<span class="chip amber">на проверке</span>{/if}
	{#if m.hidden}<span class="chip">скрыт</span>{/if}
	{#if m.pending}<span
			class="chip amber"
			title="Создано без сети — уйдёт на сервер, когда появится интернет"
			><CloudOff size={12} /> ждёт отправки</span
		>{/if}
	{#if m.file && m.file.id > 0 && canPreview(m.file.mime, m.file.name, m.file.size)}
		{@const f = m.file}
		<button
			class="ext"
			onclick={() => openFiles([f], 0, m.title)}
			aria-label="Открыть {m.title}"
			title="Открыть"><Eye size={17} /></button
		>
	{/if}
	{#if m.kind === 'link' && m.url}
		<a
			class="ext"
			href={m.url}
			target="_blank"
			rel="noopener noreferrer nofollow"
			aria-label="Открыть ссылку"><ExternalLink size={16} /></a
		>
	{/if}
	<Menu items={actions} />
</div>

<style>
	.mrow {
		transition: background-color var(--dur) var(--ease);
	}
	.mrow:hover {
		background: color-mix(in srgb, var(--surface-2) 50%, var(--surface));
	}
	.dim {
		opacity: 0.6;
	}
	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}
	.title {
		color: var(--text);
		font-weight: 560;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.meta {
		display: flex;
		align-items: center;
		gap: 4px;
		overflow: hidden;
		white-space: nowrap;
	}
	.ext {
		display: grid;
		place-items: center;
		flex: none;
		width: 36px;
		height: 36px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-3);
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease);
	}
	.ext:hover {
		background: var(--surface-2);
		color: var(--text);
	}
	.ext:hover {
		background: var(--surface-2);
		color: var(--text);
	}
</style>
