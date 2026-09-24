<script lang="ts">
	import { KINDS, type HomeworkKind } from './kinds';
	import KindIcon from './KindIcon.svelte';

	let { value = $bindable('homework') }: { value: HomeworkKind } = $props();
</script>

<fieldset class="kp">
	<legend class="label">Тип</legend>
	<div class="seg" role="radiogroup" aria-label="Тип задания">
		{#each KINDS as k (k.value)}
			<button
				type="button"
				role="radio"
				aria-checked={value === k.value}
				class:on={value === k.value}
				onclick={() => (value = k.value)}
			>
				<KindIcon kind={k.value} size={16} />
				<span>{k.label}</span>
			</button>
		{/each}
	</div>
</fieldset>

<style>
	.kp {
		border: 0;
		margin: 0;
		padding: 0;
		min-width: 0;
	}
	.seg {
		display: grid;
		grid-template-columns: repeat(5, 1fr);
		gap: 4px;
		padding: 4px;
		border-radius: 14px;
		background: var(--surface-2);
	}
	@media (max-width: 560px) {
		.seg {
			grid-template-columns: repeat(3, 1fr);
		}
	}
	button {
		display: inline-flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 3px;
		min-width: 0;
		height: 54px;
		padding: 0 4px;
		border: 0;
		border-radius: 10px;
		background: transparent;
		color: var(--text-2);
		font: inherit;
		font-size: 12.5px;
		font-weight: 550;
		transition:
			background-color var(--dur) var(--ease),
			color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	button span {
		max-width: 100%;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	button:active {
		transform: scale(0.97);
	}
	button.on {
		background: var(--surface);
		color: var(--text);
		box-shadow: 0 1px 3px rgb(16 18 24 / 0.1);
	}
</style>
