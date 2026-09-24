<script lang="ts">
	import SubjectGlyph from './SubjectGlyph.svelte';

	let {
		id,
		name,
		color,
		link = true
	}: { id: number; name: string; color: string; link?: boolean } = $props();
</script>

{#if link}
	<a class="tag" href="/subjects/{id}"
		><SubjectGlyph {id} {name} {color} size={14} bare /><span class="n">{name}</span></a
	>
{:else}
	<span class="tag"
		><SubjectGlyph {id} {name} {color} size={14} bare /><span class="n">{name}</span></span
	>
{/if}

<style>
	.tag {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-size: 13px;
		font-weight: 550;
		color: var(--text-2);
		min-width: 0;
	}
	a.tag:hover {
		color: var(--text);
		text-decoration: none;
	}
	.n {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
