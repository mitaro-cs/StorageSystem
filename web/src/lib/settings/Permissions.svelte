<script lang="ts">
	import { get, put } from '$lib/api';
	import { t } from '$lib/i18n/ru';
	import { toastError } from '$lib/toasts.svelte';
	import type { PermissionCell } from '$lib/types';

	/** groupId === null — значения по умолчанию для всего инстанса (только admin). */
	let { groupId }: { groupId: number | null } = $props();
	let cells = $state<PermissionCell[]>([]);

	const base = $derived(
		groupId === null ? '/api/admin/permissions' : `/api/groups/${groupId}/permissions`
	);

	$effect(() => {
		get<PermissionCell[]>(base).then((c) => (cells = c));
	});

	async function set(c: PermissionCell, allowed: boolean | null) {
		try {
			cells = await put<PermissionCell[]>(base, {
				role: c.role,
				permission: c.permission,
				allowed
			});
		} catch (e) {
			toastError(e);
		}
	}
</script>

<div class="list">
	{#each cells as c (c.role + c.permission)}
		<div class="list-row">
			<div class="info">
				<strong>{t.permissions[c.permission] ?? c.permission}</strong>
				<span class="faint small"
					>{t.roles[c.role]}{c.overridden === false && groupId !== null
						? ' · как на всём сайте'
						: ''}</span
				>
			</div>
			{#if groupId !== null && c.overridden}
				<button class="reset small" onclick={() => set(c, null)}>Сбросить</button>
			{/if}
			<label class="switch">
				<input
					type="checkbox"
					role="switch"
					checked={c.allowed}
					onchange={(e) => set(c, e.currentTarget.checked)}
					aria-label="{t.permissions[c.permission]}: {t.roles[c.role]}"
				/>
				<span class="track"><span class="knob"></span></span>
			</label>
		</div>
	{/each}
</div>
<p class="hint">
	{groupId === null
		? 'Значения для всех групп. Староста может изменить их в своей группе.'
		: 'Остальные права фиксированы ролью — см. документацию.'}
</p>

<style>
	.info {
		flex: 1;
		display: flex;
		flex-direction: column;
	}
	.reset {
		border: 0;
		background: none;
		color: var(--accent);
	}
	.switch {
		position: relative;
		flex: none;
		cursor: pointer;
	}
	.switch input {
		position: absolute;
		opacity: 0;
		inset: 0;
		cursor: pointer;
	}
	.track {
		display: block;
		width: 44px;
		height: 26px;
		border-radius: 13px;
		background: var(--surface-3);
		transition: background-color var(--dur) var(--ease);
	}
	.knob {
		display: block;
		width: 22px;
		height: 22px;
		margin: 2px;
		border-radius: 50%;
		background: #fff;
		box-shadow: 0 1px 3px rgb(0 0 0 / 0.25);
		transition: transform var(--dur) var(--ease);
	}
	input:checked + .track {
		background: var(--accent);
	}
	input:checked + .track .knob {
		transform: translateX(18px);
	}
	input:focus-visible + .track {
		outline: 2px solid var(--focus);
		outline-offset: 2px;
	}
</style>
