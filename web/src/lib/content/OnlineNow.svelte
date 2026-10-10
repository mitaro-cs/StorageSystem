<script lang="ts">
	import { onMount } from 'svelte';
	import { firstName } from '$lib/names';
	import { loadPresence, presence } from '$lib/presence.svelte';
	import { session } from '$lib/session.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';

	// «Сейчас на сайте» в строке под приветствием (1.0.2): точка, сколько и первые лица. Строкой –
	// чтобы появление ничего не сдвигало по высоте.
	onMount(() => void loadPresence());
	const others = $derived(presence.online.filter((p) => p.id !== session.me?.user.id));
	const names = $derived(others.map((p) => firstName(p.displayName)).join(', '));
</script>

{#if others.length}
	<a class="online" href="/members" title="На сайте: {names}">
		<span class="dot" aria-hidden="true"></span>
		<span class="stack" aria-hidden="true"
			>{#each others.slice(0, 3) as p (p.id)}<Avatar
					id={p.id}
					name={p.displayName}
					avatar={p.avatar}
					size={20}
				/>{/each}</span
		>
		<span class="num">{others.length + 1}</span> на сайте
	</a>
{/if}

<style>
	.online {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		margin-left: 10px;
		color: inherit;
		text-decoration: none;
		vertical-align: middle;
		white-space: nowrap;
	}
	.online:hover {
		color: var(--text);
	}
	.dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--ok, #22a06b);
		box-shadow: 0 0 0 3px color-mix(in srgb, var(--ok, #22a06b) 22%, transparent);
		animation: beat 2.4s ease-in-out infinite;
	}
	.stack {
		display: inline-flex;
		line-height: 0;
	}
	.stack :global(> *:not(:first-child)) {
		margin-left: -6px;
	}
	.stack :global(> *) {
		border-radius: 50%;
		box-shadow: 0 0 0 2px var(--bg);
	}
	@keyframes beat {
		50% {
			box-shadow: 0 0 0 5px color-mix(in srgb, var(--ok, #22a06b) 8%, transparent);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.dot {
			animation: none;
		}
	}
</style>
