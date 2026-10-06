<script lang="ts">
	import type { Component, Snippet } from 'svelte';

	// Раздел формы добавления: значок, заголовок и содержимое на мягкой подложке – окна пары,
	// задания, новости и материала собраны из таких блоков.
	let {
		title,
		icon: Icon,
		hint = '',
		aside,
		children
	}: {
		title: string;
		icon?: Component<{ size?: number }>;
		hint?: string;
		aside?: Snippet;
		children: Snippet;
	} = $props();
	const id = $props.id();
</script>

<section class="fsec" aria-labelledby={id}>
	<header>
		{#if Icon}<span class="ic" aria-hidden="true"><Icon size={15} /></span>{/if}
		<h3 {id}>{title}</h3>
		{#if hint}<span class="hint-s">{hint}</span>{/if}
		{#if aside}<span class="aside">{@render aside()}</span>{/if}
	</header>
	{@render children()}
</section>

<style>
	.fsec {
		display: flex;
		flex-direction: column;
		gap: 10px;
		padding: 14px 16px 16px;
		border: 1px solid var(--border);
		border-radius: 18px;
		background: color-mix(in srgb, var(--surface-2) 70%, var(--surface));
		min-width: 0;
	}
	header {
		display: flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.ic {
		display: grid;
		place-items: center;
		width: 26px;
		height: 26px;
		border-radius: 9px;
		background: var(--surface);
		color: var(--text-2);
		box-shadow: 0 0 0 1px var(--border);
	}
	h3 {
		margin: 0;
		font-size: 13.5px;
		font-weight: 650;
		letter-spacing: 0.01em;
	}
	.hint-s {
		font-size: 12.5px;
		color: var(--text-3);
	}
	.aside {
		margin-left: auto;
	}
	@media (max-width: 520px) {
		.fsec {
			padding: 12px;
			border-radius: 16px;
		}
	}
</style>
