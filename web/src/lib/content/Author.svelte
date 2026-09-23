<script lang="ts">
	import type { Person } from '$lib/types';
	import { t } from '$lib/i18n/ru';
	import Avatar from '$lib/ui/Avatar.svelte';

	/** label — короткая подпись вместо ФИО (в комментариях — имя), полное ФИО во всплывающей подсказке. */
	let { person, size = 28, label }: { person: Person; size?: number; label?: string } = $props();
	const name = $derived(person.deleted ? t.common.deletedUser : (label ?? person.displayName));
</script>

<span class="author">
	<Avatar
		id={person.id}
		name={person.deleted ? '?' : person.displayName}
		avatar={person.avatar}
		{size}
	/>
	<span
		class="name"
		class:deleted={person.deleted}
		title={label && !person.deleted ? person.displayName : undefined}>{name}</span
	>
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
