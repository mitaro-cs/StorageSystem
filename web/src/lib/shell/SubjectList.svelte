<script lang="ts">
	import { page } from '$app/state';
	import { sortedSubjects } from '$lib/data.svelte';
	import { session } from '$lib/session.svelte';
	import { Pin } from '@lucide/svelte';

	let { onnavigate }: { onnavigate?: () => void } = $props();
	const list = $derived(sortedSubjects(session.groupId));
</script>

<ul class="subjects">
	{#each list as s (s.id)}
		<li>
			<a
				href="/subjects/{s.id}"
				class:active={page.url.pathname.startsWith(`/subjects/${s.id}`)}
				onclick={onnavigate}
			>
				<span class="dot" style:background={s.color}></span>
				<span class="name">{s.name}</span>
				{#if s.pinned}<Pin size={12} class="pin" aria-label="закреплён" />{/if}
				{#if s.groups.length > 1}<span class="shared" title="Общий предмет">⋈</span>{/if}
			</a>
		</li>
	{:else}
		<li class="none faint small">Предметов пока нет</li>
	{/each}
</ul>

<style>
	.subjects {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 1px;
	}
	a {
		display: flex;
		align-items: center;
		gap: 10px;
		height: 34px;
		padding: 0 10px;
		border-radius: 9px;
		color: var(--text-2);
		font-size: 14px;
	}
	a:hover {
		background: var(--surface-2);
		color: var(--text);
		text-decoration: none;
	}
	a.active {
		background: var(--surface-2);
		color: var(--text);
		font-weight: 550;
	}
	.dot {
		flex: none;
		width: 10px;
		height: 10px;
		border-radius: 3.5px;
	}
	.name {
		flex: 1;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	a :global(.pin) {
		color: var(--text-3);
		flex: none;
	}
	.shared {
		font-size: 12px;
		color: var(--text-3);
	}
	.none {
		padding: 6px 10px;
	}
</style>
