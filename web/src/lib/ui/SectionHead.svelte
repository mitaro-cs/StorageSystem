<script lang="ts">
	import type { Component, Snippet } from 'svelte';

	// Заголовок раздела настроек: цветная иконка, название и одна фраза — что здесь меняется.
	// Разделы различаются с первого взгляда и не сливаются в одну белую простыню.
	interface Props {
		icon: Component<{ size?: number | string }>;
		title: string;
		text?: string;
		tone?: 'blue' | 'green' | 'amber' | 'violet' | 'red' | 'teal' | 'gray';
		id?: string;
		children?: Snippet;
	}

	let { icon: Icon, title, text = '', tone = 'gray', id, children }: Props = $props();
</script>

<header class="sh">
	<span class="ic {tone}" aria-hidden="true"><Icon size={20} /></span>
	<div class="t">
		<h2 {id}>{title}</h2>
		{#if text}<p>{text}</p>{/if}
	</div>
	{#if children}<div class="aside">{@render children()}</div>{/if}
</header>

<style>
	.sh {
		display: flex;
		align-items: flex-start;
		gap: 14px;
	}
	.ic {
		flex: none;
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: 12px;
		color: var(--c);
		background: color-mix(in srgb, var(--c) 13%, transparent);
	}
	.blue {
		--c: #3a6ff0;
	}
	.green {
		--c: #1f9d57;
	}
	.amber {
		--c: #d98a00;
	}
	.violet {
		--c: #8b5cf6;
	}
	.red {
		--c: #e0483e;
	}
	.teal {
		--c: #0e9fa8;
	}
	.gray {
		--c: var(--text-2);
	}
	.t {
		flex: 1;
		min-width: 0;
		padding-top: 1px;
	}
	h2 {
		margin: 0;
		font-size: 17px;
		line-height: 1.3;
		scroll-margin-top: 90px;
	}
	p {
		margin: 3px 0 0;
		color: var(--text-2);
		font-size: 14px;
		line-height: 1.45;
	}
	.aside {
		flex: none;
		align-self: center;
	}
</style>
