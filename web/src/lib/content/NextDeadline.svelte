<script lang="ts">
	import { Clock, Paperclip, MessageCircle } from '@lucide/svelte';
	import type { Homework } from '$lib/types';
	import { fmtDue, relativeDay } from '$lib/format';
	import SubjectArt from '$lib/ui/SubjectArt.svelte';

	// Инверсная карточка ближайшего дедлайна: чёрная в светлой теме, белая в тёмной.
	let { item, now = Date.now() }: { item: Homework; now?: number } = $props();

	const badge = $derived.by(() => {
		const rel = relativeDay(item.dueAt, now);
		return rel.charAt(0).toUpperCase() + rel.slice(1);
	});
</script>

<a class="next" href="/homework/{item.id}">
	<span class="cover">
		<SubjectArt {...item.subject} class="fill" />
		<span class="badge num">{badge}</span>
	</span>
	<span class="info">
		<span class="kicker">Ближайший дедлайн</span>
		<strong class="title">{item.title}</strong>
		<span class="subject">{item.subject.name}</span>
		<span class="facts">
			<span><Clock size={15} /> <span class="num">{fmtDue(item.dueAt, now)}</span></span>
			{#if item.attachments.length}<span
					><Paperclip size={15} /> <span class="num">{item.attachments.length}</span></span
				>{/if}
			{#if item.comments}<span
					><MessageCircle size={15} /> <span class="num">{item.comments}</span></span
				>{/if}
		</span>
	</span>
</a>

<style>
	.next {
		display: grid;
		grid-template-columns: minmax(120px, 42%) 1fr;
		gap: var(--s4);
		padding: 12px;
		border-radius: var(--r-xl);
		background: var(--inverse);
		color: var(--inverse-text);
		box-shadow: var(--shadow-2);
		transition: transform var(--dur) var(--ease);
	}
	.next:hover {
		text-decoration: none;
		transform: translateY(-2px);
	}
	.next:active {
		transform: scale(0.99);
	}
	.cover {
		position: relative;
		min-height: 168px;
	}
	.cover :global(.fill) {
		position: absolute;
		inset: 0;
		border-radius: 18px;
	}
	.badge {
		position: absolute;
		left: 10px;
		bottom: 10px;
		padding: 6px 12px;
		border-radius: 12px;
		background: rgb(0 0 0 / 0.78);
		color: #fff;
		font: 600 14px var(--mono);
		letter-spacing: -0.02em;
		backdrop-filter: blur(8px);
		-webkit-backdrop-filter: blur(8px);
	}
	.info {
		display: flex;
		flex-direction: column;
		gap: 4px;
		min-width: 0;
		padding: 6px 4px 2px 0;
	}
	.kicker {
		font-size: 12px;
		font-weight: 600;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--inverse-muted);
	}
	.title {
		font-size: 21px;
		line-height: 1.2;
		letter-spacing: -0.025em;
		display: -webkit-box;
		-webkit-line-clamp: 3;
		line-clamp: 3;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}
	.subject {
		color: var(--inverse-muted);
		font-size: 15px;
	}
	.facts {
		display: inline-flex;
		flex-wrap: wrap;
		align-self: flex-start;
		align-items: center;
		margin-top: auto;
		padding: 8px 4px;
		border-radius: 14px;
		background: var(--inverse-2);
		color: var(--inverse-muted);
		font-size: 13.5px;
	}
	.facts > span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 0 10px;
	}
	.facts > span + span {
		border-left: 1px solid color-mix(in srgb, var(--inverse-muted) 40%, transparent);
	}
	@media (max-width: 420px) {
		.next {
			gap: var(--s3);
		}
		.cover {
			min-height: 148px;
		}
		.title {
			font-size: 18px;
		}
	}
</style>
