<script lang="ts">
	import {
		BookOpen,
		FlaskConical,
		ClipboardCheck,
		BadgeCheck,
		GraduationCap
	} from '@lucide/svelte';

	let { kind, size = 14 }: { kind: string; size?: number } = $props();
</script>

{#if kind === 'lab'}<FlaskConical {size} />{:else if kind === 'test'}<ClipboardCheck
		{size}
	/>{:else if kind === 'credit'}<BadgeCheck {size} />{:else if kind === 'exam'}<GraduationCap
		{size}
	/>{:else}<BookOpen {size} />{/if}
