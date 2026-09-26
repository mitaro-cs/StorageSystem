<script lang="ts">
	import { MessageCircle, Pin, EyeOff, CloudOff, Zap } from '@lucide/svelte';
	import type { NewsItem } from '$lib/types';
	import { fmtAgo } from '$lib/format';
	import { isMulti } from '$lib/session.svelte';
	import Author from './Author.svelte';
	import { firstName, lastName } from '$lib/names';
	import SubjectTag from '$lib/ui/SubjectTag.svelte';
	import Menu, { type MenuItem } from '$lib/ui/Menu.svelte';
	import Prose from '$lib/ui/Prose.svelte';

	interface Props {
		item: NewsItem;
		full?: boolean;
		/** Карточка на главной: текст в две строки. */
		compact?: boolean;
		actions?: MenuItem[];
	}

	let { item, full = false, compact = false, actions = [] }: Props = $props();

	/** В списках — «Имя Фамилия»: ФИО целиком не помещается в строку на телефоне. */
	const shortName = (fio: string) => [firstName(fio), lastName(fio)].filter(Boolean).join(' ');
</script>

<article
	class="news card"
	class:urgent={item.urgent}
	class:hidden={item.hidden}
	class:compact
	style:--subject={item.subject?.color ?? 'var(--border-strong)'}
>
	{#if item.urgent}
		<div class="band"><Zap size={15} strokeWidth={2.4} /> Срочно</div>
	{/if}
	<header>
		<span class="byline">
			<Author person={item.author} label={full ? undefined : shortName(item.author.displayName)} />
			<span class="faint small num when">{fmtAgo(item.createdAt)}</span>
		</span>
		<span class="spacer"></span>
		{#if item.pinned}<span class="faint" title="Закреплено"><Pin size={15} /></span>{/if}
		<Menu items={actions} />
	</header>
	<div class="meta">
		{#if item.hidden}<span class="chip"><EyeOff size={13} /> Скрыто</span>{/if}
		{#if item.pending}<span
				class="chip amber"
				title="Создано без сети — уйдёт на сервер, когда появится интернет"
				><CloudOff size={12} /> ждёт отправки</span
			>{/if}
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
		gap: 12px;
		padding: 20px 20px var(--s4);
		transition:
			box-shadow var(--dur) var(--ease),
			transform var(--dur) var(--ease);
	}
	.news:has(.stretched):hover {
		box-shadow: var(--shadow-2);
	}
	/* Стиль «Цвет предметов» (Профиль → Оформление): карточка в цвете своего предмета. */
	:global(:root[data-style='tint']) .news:not(.urgent) {
		background: color-mix(in srgb, var(--subject) 13%, var(--surface));
	}
	/* Полоса слева — цвет предмета (у новостей без предмета — нейтральная) */
	.news::before {
		content: '';
		position: absolute;
		left: 8px;
		top: 20px;
		bottom: 20px;
		width: 4px;
		border-radius: 2px;
		background: var(--subject);
	}
	/* Срочное — янтарная плашка по верху карточки и обводка */
	.urgent {
		box-shadow:
			0 0 0 2px var(--urgent),
			var(--shadow-2);
	}
	.urgent::before {
		top: 48px;
	}
	.band {
		display: flex;
		align-items: center;
		gap: 6px;
		margin: -20px -20px 0;
		padding: 9px 20px;
		border-top-left-radius: inherit;
		border-top-right-radius: inherit;
		background: var(--urgent);
		color: var(--urgent-text);
		font-size: 13px;
		font-weight: 750;
		letter-spacing: 0.06em;
		text-transform: uppercase;
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
		font-size: 19px;
		letter-spacing: -0.02em;
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
	.compact {
		gap: 8px;
		padding: 16px 18px 14px;
	}
	.compact h3 {
		font-size: 18px;
	}
	.compact :global(.clamp) {
		-webkit-line-clamp: 2;
		line-clamp: 2;
	}
	.compact::before {
		left: 6px;
		top: 16px;
		bottom: 16px;
	}
	.compact .band {
		margin: -16px -18px 0;
		padding: 8px 18px;
	}
	.compact.urgent::before {
		top: 42px;
	}
	.news :global(.body:not(.clamp)) {
		pointer-events: auto;
	}
	.byline {
		display: flex;
		align-items: center;
		gap: 10px;
		min-width: 0;
	}
	.when {
		flex: none;
		white-space: nowrap;
	}
	/* Телефон: время — под именем, чтобы имя не обрезалось до пары букв. */
	@media (max-width: 480px) {
		.byline {
			flex-direction: column;
			align-items: flex-start;
			gap: 0;
		}
		.byline :global(.author) {
			max-width: 100%;
		}
		.when {
			margin: -3px 0 0 36px;
		}
	}
	footer {
		display: flex;
		align-items: center;
		gap: 6px;
	}
</style>
