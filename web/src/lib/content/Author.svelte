<script lang="ts">
	import type { Person } from '$lib/types';
	import { t } from '$lib/i18n/ru';
	import Avatar from '$lib/ui/Avatar.svelte';

	let { person, size = 28 }: { person: Person; size?: number } = $props();
	const name = $derived(person.deleted ? t.common.deletedUser : person.displayName);
</script>

<span class="author">
	<Avatar
		id={person.id}
		name={person.deleted ? '?' : person.displayName}
		avatar={person.avatar}
		{size}
	/>
	<span class="name" class:deleted={person.deleted}>{name}</span>
</span>

<style>
	.author {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		min-width: 0;
	}
	.name {
		font-weight: 560;
		font-size: 14px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.deleted {
		font-weight: 450;
		font-style: italic;
		color: var(--text-3);
	}
</style>
