<script lang="ts">
	import { page } from '$app/state';
	import { sortedSubjects } from '$lib/data.svelte';
	import { session } from '$lib/session.svelte';
	import { Pin } from '@lucide/svelte';
	import SubjectGlyph from '$lib/ui/SubjectGlyph.svelte';

	let { onnavigate }: { onnavigate?: () => void } = $props();
	// Свои предметы: предметы другой подгруппы, скрытые у себя, — только в «Предметах».
	const list = $derived(sortedSubjects(session.groupId, true));
</script>

<ul class="subjects">
	{#each list as s (s.id)}
		<li>
			<a
				href="/subjects/{s.id}"
				class:active={page.url.pathname.startsWith(`/subjects/${s.id}`)}
				onclick={onnavigate}
			>
				<SubjectGlyph name={s.name} color={s.color} icon={s.icon} size={26} />
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
		height: 42px;
		padding: 0 12px 0 8px;
		border-radius: var(--r-full);
		color: var(--text-2);
		font-size: 14.5px;
	}
	a:hover {
		background: var(--surface);
		color: var(--text);
		text-decoration: none;
	}
	a.active {
		background: var(--surface);
		color: var(--text);
		font-weight: 600;
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
