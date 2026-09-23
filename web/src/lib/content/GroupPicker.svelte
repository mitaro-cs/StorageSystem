<script lang="ts">
	import type { GroupRef } from '$lib/types';

	let { options, selected = $bindable() }: { options: GroupRef[]; selected: number[] } = $props();

	function toggle(id: number) {
		selected = selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id];
	}
</script>

{#if options.length > 1}
	<fieldset class="picker">
		<legend class="label">Для кого</legend>
		<div class="opts">
			{#each options as g (g.id)}
				<button
					type="button"
					class="opt"
					class:on={selected.includes(g.id)}
					aria-pressed={selected.includes(g.id)}
					onclick={() => toggle(g.id)}>{g.name}</button
				>
			{/each}
		</div>
	</fieldset>
{/if}

<style>
	.picker {
		border: 0;
		padding: 0;
		margin: 0;
	}
	.opts {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;
	}
	.opt {
		height: 32px;
		padding: 0 12px;
		border: 1px solid var(--border-strong);
		border-radius: 999px;
		background: var(--surface);
		font-size: 13.5px;
		font-weight: 550;
		color: var(--text-2);
		transition: all var(--dur) var(--ease);
	}
	.opt.on {
		background: var(--accent);
		border-color: var(--accent);
		color: var(--accent-text);
	}
</style>
