<script lang="ts">
	let {
		id,
		name,
		color,
		link = true
	}: { id: number; name: string; color: string; link?: boolean } = $props();
</script>

{#if link}
	<a class="tag" href="/subjects/{id}"><span class="dot" style:background={color}></span>{name}</a>
{:else}
	<span class="tag"><span class="dot" style:background={color}></span>{name}</span>
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
	.dot {
		flex: none;
		width: 8px;
		height: 8px;
		border-radius: 3px;
	}
</style>
