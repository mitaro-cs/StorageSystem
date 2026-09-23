<script lang="ts">
	let {
		checked = $bindable(false),
		label,
		onchange
	}: { checked?: boolean; label: string; onchange?: (v: boolean) => void } = $props();
</script>

<button
	type="button"
	class="switch"
	class:on={checked}
	role="switch"
	aria-checked={checked}
	aria-label={label}
	onclick={() => {
		checked = !checked;
		onchange?.(checked);
	}}
>
	<span class="knob"></span>
</button>

<style>
	.switch {
		position: relative;
		flex: none;
		width: 52px;
		height: 32px;
		padding: 0;
		border: 0;
		border-radius: var(--r-full);
		background: var(--surface-3);
		transition: background-color 200ms var(--ease);
		-webkit-tap-highlight-color: transparent;
	}
	.switch.on {
		background: var(--accent);
	}
	.knob {
		position: absolute;
		top: 3px;
		left: 3px;
		width: 26px;
		height: 26px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 2px 6px rgb(0 0 0 / 0.2);
		transition: transform 220ms var(--ease);
	}
	.on .knob {
		transform: translateX(20px);
		background: var(--accent-text);
	}
</style>
