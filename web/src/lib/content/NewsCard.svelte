<script lang="ts">
	import { MessageCircle, Pin, EyeOff } from '@lucide/svelte';
	import type { NewsItem } from '$lib/types';
	import { fmtAgo } from '$lib/format';
	import { isMulti } from '$lib/session.svelte';
	import Author from './Author.svelte';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Prose from '$lib/ui/Prose.svelte';

	interface Props {
		item: NewsItem;
		full?: boolean;
		actions?: MenuItem[];
	}

	let { item, full = false, actions = [] }: Props = $props();
</script>

<article class="news card" class:urgent={item.urgent} class:hidden={item.hidden}>
	<header>
		<Author person={item.author} />
		<span class="faint small num">{fmtAgo(item.createdAt)}</span>
		<span class="spacer"></span>
		{#if item.pinned}<span class="faint" title="Закреплено"><Pin size={15} /></span>{/if}
		<Menu items={actions} />
	</header>
	<div class="meta">
		{#if item.urgent}<span class="chip amber">Срочно</span>{/if}
		{#if item.hidden}<span class="chip"><EyeOff size={13} /> Скрыто</span>{/if}
		{#if item.subject}<SubjectTag {...item.subject} />{/if}
		{#if isMulti()}{#each item.groups as g (g.id)}<span class="chip">{g.name}</span>{/each}{/if}
	</div>
	{#if full}
		<h1 class="title-full">{item.title}</h1>
	{:else}
		<h3><a href="/news/{item.id}" class="stretched">{item.title}</a></h3>
	{/if}
	{#if item.bodyHtml}
		<Prose html={item.bodyHtml} class="body {full ? '' : 'clamp'}" />
	{/if}
	{#if !full}
		<footer class="faint small">
			<MessageCircle size={15} />
			<span class="num">{item.comments}</span>
		</footer>
	{/if}
</article>

<style>
	.news {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: var(--s4) var(--s4) var(--s3);
		transition:
			box-shadow var(--dur) var(--ease),
			transform var(--dur) var(--ease);
	}
	.news:has(.stretched):hover {
		box-shadow: var(--shadow-2);
	}
	.urgent {
		box-shadow:
			inset 3px 0 0 var(--amber),
			var(--shadow-1);
	}
	.hidden {
		opacity: 0.7;
	}
	header {
		display: flex;
		align-items: center;
		gap: 10px;
		position: relative;
		z-index: 2;
	}
	.meta {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		gap: 6px 10px;
		position: relative;
		z-index: 2;
	}
	.meta:empty {
		display: none;
	}
	h3 {
		font-size: 17px;
	}
	h3 a {
		color: var(--text);
	}
	.stretched::after {
		content: '';
		position: absolute;
		inset: 0;
		z-index: 1;
	}
	.title-full {
		font-size: 24px;
	}
	.news :global(.body) {
		color: var(--text);
		position: relative;
		z-index: 2;
		pointer-events: none;
	}
	.news :global(.body a) {
		pointer-events: auto;
	}
	.news :global(.clamp) {
		display: -webkit-box;
		-webkit-line-clamp: 4;
		line-clamp: 4;
		-webkit-box-orient: vertical;
		overflow: hidden;
		color: var(--text-2);
	}
	.news :global(.body:not(.clamp)) {
		pointer-events: auto;
	}
	footer {
		display: flex;
		align-items: center;
		gap: 6px;
	}
</style>
