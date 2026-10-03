<script lang="ts">
	import { CloudOff, ExternalLink, Eye, Pin } from '@lucide/svelte';
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

<!-- Вся строка — ссылка на материал; «Открыть», ссылка наружу и меню — поверх неё. -->
<div class="list-row mrow" class:dim={m.hidden} class:pinned={!!m.pinnedAt}>
	<FileIcon mime={m.file?.mime} link={m.kind === 'link'} note={m.kind === 'note'} />
	<div class="main">
		<a class="title" href="/materials/{m.id}"
			>{#if m.pinnedAt}<Pin size={13} aria-label="Закреплено" />
			{/if}{m.title}</a
		>
		{#if m.kind === 'note' && m.description !== m.title}
			<!-- Сообщение: начало текста прямо в списке. -->
			<span class="text small">{m.description.replace(m.title, '').trim()}</span>
		{/if}
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
		position: relative;
		cursor: pointer;
		transition: background-color var(--dur) var(--ease);
	}
	.title::after {
		content: '';
		position: absolute;
		inset: 0;
		z-index: 1;
	}
	.title:focus-visible {
		outline: none;
	}
	.mrow:has(.title:focus-visible) {
		outline: 2px solid var(--focus);
		outline-offset: -2px;
	}
	.mrow > :global(.menu),
	.ext {
		position: relative;
		z-index: 2;
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
	.text {
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
		color: var(--text-2);
		white-space: pre-line;
	}
	.pinned {
		background: color-mix(in srgb, var(--accent) 6%, transparent);
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
</style>
