<script lang="ts">
	import { hueFor, initials } from '$lib/format';

	interface Props {
		id: number;
		name: string;
		avatar?: string | null;
		size?: number;
		kind?: 'user' | 'group' | 'subject';
		square?: boolean;
		/** Ровное кольцо вокруг — рисуется на самом аватаре, а не на родителе со строчной высотой. */
		ring?: boolean;
	}

	let {
		id,
		name,
		avatar = null,
		size = 32,
		kind = 'user',
		square = false,
		ring = false
	}: Props = $props();

	const hue = $derived(
		hueFor(id + (kind === 'group' ? 1_000_003 : kind === 'subject' ? 2_000_029 : 0))
	);
	const src = $derived(avatar ? `/api/avatars/${avatar}-${size > 64 ? 256 : 64}.webp` : null);
	const letters = $derived(initials(name || '?'));
</script>

<span
	class="avatar"
	class:ring
	class:square
	style:width="{size}px"
	style:height="{size}px"
	style:--h={hue}
	role="img"
	aria-label={name}
>
	{#if src}
		<img {src} alt="" width={size} height={size} loading="lazy" decoding="async" />
	{:else}
		<svg viewBox="0 0 40 40" aria-hidden="true">
			<rect width="40" height="40" class="bg" />
			<text x="20" y="20" dominant-baseline="central" text-anchor="middle">{letters}</text>
		</svg>
	{/if}
</span>

<style>
	.avatar {
		display: inline-block;
		flex: none;
		vertical-align: middle;
		border-radius: 50%;
		overflow: hidden;
		background: var(--surface-2);
		/* Скругление с обрезкой без «лесенки» по краю в Safari. */
		isolation: isolate;
	}
	.ring {
		box-shadow:
			0 0 0 3px var(--surface),
			0 0 0 4px var(--border);
	}
	.square {
		border-radius: 28%;
	}
	img,
	svg {
		display: block;
		width: 100%;
		height: 100%;
		object-fit: cover;
		border-radius: inherit;
	}
	.bg {
		fill: hsl(var(--h) 55% 88%);
	}
	text {
		fill: hsl(var(--h) 45% 32%);
		font: 600 15px var(--font);
		letter-spacing: 0.02em;
	}
	:global([data-theme='dark']) .bg {
		fill: hsl(var(--h) 30% 26%);
	}
	:global([data-theme='dark']) text {
		fill: hsl(var(--h) 60% 82%);
	}
	@media (prefers-color-scheme: dark) {
		:global(:root:not([data-theme='light'])) .bg {
			fill: hsl(var(--h) 30% 26%);
		}
		:global(:root:not([data-theme='light'])) text {
			fill: hsl(var(--h) 60% 82%);
		}
	}
</style>
