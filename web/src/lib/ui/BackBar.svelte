<script lang="ts">
	import type { Snippet } from 'svelte';
	import { ArrowLeft } from '@lucide/svelte';

	// Шапка страницы деталей: «← Раздел» и действия справа (как «← Austria  [Map]»).
	let { href, label, children }: { href: string; label: string; children?: Snippet } = $props();
</script>

<div class="backbar">
	<a
		class="back"
		{href}
		onclick={(e) => {
			if (history.length > 1) {
				e.preventDefault();
				history.back();
			}
		}}
	>
		<span class="circle"><ArrowLeft size={20} /></span>
		<span class="name">{label}</span>
	</a>
	{#if children}<div class="actions">{@render children()}</div>{/if}
</div>

<style>
	.backbar {
		display: flex;
		align-items: center;
		gap: var(--s3);
		margin-bottom: var(--s5);
	}
	/* На телефоне заменяет верхнюю панель: липкая, с матовым фоном */
	@media (max-width: 899px) {
		.backbar {
			position: sticky;
			top: 0;
			z-index: 30;
			margin: calc(-1 * var(--s4)) calc(-1 * var(--s4)) var(--s4);
			padding: calc(12px + env(safe-area-inset-top)) var(--s4) 12px;
			background: color-mix(in srgb, var(--bg) 80%, transparent);
			backdrop-filter: blur(24px) saturate(1.5);
			-webkit-backdrop-filter: blur(24px) saturate(1.5);
		}
	}
	.back {
		flex: 1;
		display: inline-flex;
		align-items: center;
		gap: 12px;
		min-width: 0;
		color: var(--text);
		font-size: 19px;
		font-weight: 600;
		letter-spacing: -0.02em;
	}
	.back:hover {
		text-decoration: none;
	}
	.back:hover .circle {
		background: var(--surface-2);
	}
	.name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.actions {
		display: flex;
		align-items: center;
		gap: 8px;
	}
</style>
