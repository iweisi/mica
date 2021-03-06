/*
 * Copyright (c) 2019-2029, Dreamlu 卢春梦 (596392912@qq.com & www.dreamlu.net).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dreamlu.mica.core.beans;

import net.dreamlu.mica.core.utils.BeanUtil;
import net.dreamlu.mica.core.utils.ClassUtil;
import net.dreamlu.mica.core.utils.ReflectUtil;
import net.dreamlu.mica.core.utils.StringUtil;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Label;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.cglib.core.*;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * spring cglib 魔改
 *
 * <p>
 *     1. 支持链式 bean，支持 map
 *     2. ClassLoader 跟 target 保持一致
 * </p>
 *
 * @author L.cm
 */
public abstract class MicaBeanCopier {
	private static final Type CONVERTER = TypeUtils.parseType("org.springframework.cglib.core.Converter");
	private static final Type BEAN_COPIER = TypeUtils.parseType(MicaBeanCopier.class.getName());
	private static final Signature COPY = new Signature("copy", Type.VOID_TYPE, new Type[]{Constants.TYPE_OBJECT, Constants.TYPE_OBJECT, CONVERTER});
	private static final Signature CONVERT = TypeUtils.parseSignature("Object convert(Object, Class, Object)");
	private static final Type BEAN_MAP = TypeUtils.parseType(Map.class.getName());
	private static final Signature BEAN_MAP_GET = TypeUtils.parseSignature("Object get(Object)");
	/**
	 * The map to store {@link MicaBeanCopier} of source type and class type for copy.
	 */
	private static final ConcurrentMap<MicaBeanCopierKey, MicaBeanCopier> BEAN_COPIER_MAP = new ConcurrentHashMap<>();

	public static MicaBeanCopier create(Class source, Class target, boolean useConverter) {
		return MicaBeanCopier.create(source, target, useConverter, false);
	}

	public static MicaBeanCopier create(Class source, Class target, boolean useConverter, boolean nonNull) {
		MicaBeanCopierKey copierKey = new MicaBeanCopierKey(source, target, useConverter, nonNull);
		// 利用 ConcurrentMap 缓存 提高性能，接近 直接 get set
		return BEAN_COPIER_MAP.computeIfAbsent(copierKey, key -> {
			Generator gen = new Generator();
			gen.setSource(key.getSource());
			gen.setTarget(key.getTarget());
			gen.setUseConverter(key.isUseConverter());
			gen.setNonNull(key.isNonNull());
			return gen.create(key);
		});
	}

	abstract public void copy(Object from, Object to, Converter converter);

	public static class Generator extends AbstractClassGenerator {
		private static final Source SOURCE = new Source(MicaBeanCopier.class.getName());
		private Class source;
		private Class target;
		private boolean useConverter;
		private boolean nonNull;

		Generator() {
			super(SOURCE);
		}

		public void setSource(Class source) {
			if (!Modifier.isPublic(source.getModifiers())) {
				setNamePrefix(source.getName());
			}
			this.source = source;
		}

		public void setTarget(Class target) {
			if (!Modifier.isPublic(target.getModifiers())) {
				setNamePrefix(target.getName());
			}
			this.target = target;
		}

		public void setUseConverter(boolean useConverter) {
			this.useConverter = useConverter;
		}

		public void setNonNull(boolean nonNull) {
			this.nonNull = nonNull;
		}

		@Override
		protected ClassLoader getDefaultClassLoader() {
			// L.cm 保证 和 返回使用同一个 ClassLoader
			return target.getClassLoader();
		}

		@Override
		protected ProtectionDomain getProtectionDomain() {
			return ReflectUtils.getProtectionDomain(source);
		}

		@Override
		public MicaBeanCopier create(Object key) {
			return (MicaBeanCopier) super.create(key);
		}

		@Override
		public void generateClass(ClassVisitor v) {
			Type sourceType = Type.getType(source);
			Type targetType = Type.getType(target);
			ClassEmitter ce = new ClassEmitter(v);
			ce.begin_class(Constants.V1_2,
				Constants.ACC_PUBLIC,
				getClassName(),
				BEAN_COPIER,
				null,
				Constants.SOURCE_FILE);

			EmitUtils.null_constructor(ce);
			CodeEmitter e = ce.begin_method(Constants.ACC_PUBLIC, COPY, null);

			// map 单独处理
			if (Map.class.isAssignableFrom(source)) {
				generateClassFormMap(ce, e, sourceType, targetType);
				return;
			}

			// 2018.12.27 by L.cm 支持链式 bean
			// 注意：此处需兼容链式bean 使用了 spring 的方法，比较耗时
			PropertyDescriptor[] getters = ReflectUtil.getBeanGetters(source);
			PropertyDescriptor[] setters = ReflectUtil.getBeanSetters(target);
			Map<String, PropertyDescriptor> names = new HashMap<>(16);
			for (PropertyDescriptor getter : getters) {
				names.put(getter.getName(), getter);
			}

			Local targetLocal = e.make_local();
			Local sourceLocal = e.make_local();
			e.load_arg(1);
			e.checkcast(targetType);
			e.store_local(targetLocal);
			e.load_arg(0);
			e.checkcast(sourceType);
			e.store_local(sourceLocal);

			for (PropertyDescriptor setter : setters) {
				String propName = setter.getName();

				CopyProperty targetIgnoreCopy = ReflectUtil.getAnnotation(target, propName, CopyProperty.class);
				// set 上有忽略的 注解
				if (targetIgnoreCopy != null) {
					if (targetIgnoreCopy.ignore()) {
						continue;
					}
					// 注解上的别名，如果别名不为空，使用别名
					String aliasTargetPropName = targetIgnoreCopy.value();
					if (StringUtil.isNotBlank(aliasTargetPropName)) {
						propName = aliasTargetPropName;
					}
				}
				// 找到对应的 get
				PropertyDescriptor getter = names.get(propName);
				// 没有 get 跳出
				if (getter == null) {
					continue;
				}

				MethodInfo read = ReflectUtils.getMethodInfo(getter.getReadMethod());
				Method writeMethod = setter.getWriteMethod();
				MethodInfo write = ReflectUtils.getMethodInfo(writeMethod);
				Type returnType = read.getSignature().getReturnType();
				Type setterType = write.getSignature().getArgumentTypes()[0];

				// L.cm 2019.01.12 优化逻辑，先判断类型，类型一致直接 set，不同再判断 是否 类型转换
				// nonNull Label
				Label l0 = e.make_label();
				if (compatible(getter, setter)) {
					// 2018.12.27 by L.cm 支持链式 bean
					e.load_local(targetLocal);
					e.load_local(sourceLocal);
					e.invoke(read);
					e.box(returnType);
					if (nonNull) {
						Local var = e.make_local();
						e.store_local(var);
						e.load_local(var);
						// nonNull Label
						e.ifnull(l0);
						e.load_local(targetLocal);
						e.load_local(var);
					}
					e.unbox_or_zero(setterType);
					// 构造 set 方法
					invokeWrite(e, write, writeMethod, nonNull, l0);
				} else if (useConverter) {
					e.load_local(targetLocal);
					e.load_arg(2);
					e.load_local(sourceLocal);
					e.invoke(read);
					e.box(returnType);

					if (nonNull) {
						Local var = e.make_local();
						e.store_local(var);
						e.load_local(var);
						e.ifnull(l0);
						e.load_local(targetLocal);
						e.load_arg(2);
						e.load_local(var);
					}

					EmitUtils.load_class(e, setterType);
					// 更改成了属性名，之前是 set 方法名
					e.push(propName);
					e.invoke_interface(CONVERTER, CONVERT);
					e.unbox_or_zero(setterType);

					// 构造 set 方法
					invokeWrite(e, write, writeMethod, nonNull, l0);
				}
			}
			e.return_value();
			e.end_method();
			ce.end_class();
		}

		private static boolean compatible(PropertyDescriptor getter, PropertyDescriptor setter) {
			Class<?> setterPropertyType = setter.getPropertyType();
			Class<?> getterPropertyType = getter.getPropertyType();
			// 使用 spring 的工具类 优化
			return ClassUtil.isAssignable(setterPropertyType, getterPropertyType);
		}

		private static void invokeWrite(CodeEmitter e, MethodInfo write, Method writeMethod, boolean nonNull, Label l0) {
			// 返回值，判断 链式 bean
			Class<?> returnType = writeMethod.getReturnType();
			e.invoke(write);
			// 链式 bean，有返回值需要 pop
			if (!returnType.equals(Void.TYPE)) {
				e.pop();
			}
			if (nonNull) {
				e.visitLabel(l0);
			}
		}

		@Override
		protected Object firstInstance(Class type) {
			return BeanUtil.newInstance(type);
		}

		@Override
		protected Object nextInstance(Object instance) {
			return instance;
		}

		/**
		 * 处理 map 的 copy
		 * @param ce ClassEmitter
		 * @param e CodeEmitter
		 * @param sourceType sourceType
		 * @param targetType targetType
		 */
		public void generateClassFormMap(ClassEmitter ce, CodeEmitter e, Type sourceType, Type targetType) {
			// 2018.12.27 by L.cm 支持链式 bean
			PropertyDescriptor[] setters = ReflectUtil.getBeanSetters(target);

			// 入口变量
			Local targetLocal = e.make_local();
			Local sourceLocal = e.make_local();
			e.load_arg(1);
			e.checkcast(targetType);
			e.store_local(targetLocal);
			e.load_arg(0);
			e.checkcast(sourceType);
			e.store_local(sourceLocal);
			Type mapBox = Type.getType(Object.class);

			for (PropertyDescriptor setter : setters) {
				String propName = setter.getName();

				// set 上有忽略的 注解
				CopyProperty targetIgnoreCopy = ReflectUtil.getAnnotation(target, propName, CopyProperty.class);
				if (targetIgnoreCopy != null) {
					if (targetIgnoreCopy.ignore()) {
						continue;
					}
					// 注解上的别名
					String aliasTargetPropName = targetIgnoreCopy.value();
					if (StringUtil.isNotBlank(aliasTargetPropName)) {
						propName = aliasTargetPropName;
					}
				}

				Method writeMethod = setter.getWriteMethod();
				MethodInfo write = ReflectUtils.getMethodInfo(writeMethod);
				Type setterType = write.getSignature().getArgumentTypes()[0];

				e.load_local(targetLocal);
				e.load_local(sourceLocal);

				e.push(propName);
				// 执行 map get
				e.invoke_interface(BEAN_MAP, BEAN_MAP_GET);
				// box 装箱，避免 array[] 数组问题
				e.box(mapBox);

				// 生成变量
				Local var = e.make_local();
				e.store_local(var);
				e.load_local(var);

				Label l0 = e.make_label();
				if (useConverter) {
					e.ifnull(l0);
					e.load_local(targetLocal);
					e.load_arg(2);
					e.load_local(var);

					EmitUtils.load_class(e, setterType);
					// 更改成了属性名，之前是 set 方法名
					e.push(propName);
					e.invoke_interface(CONVERTER, CONVERT);
				} else {
					// 注意：L.cm 2019.01.13，copy map时，是以 bean 的属性为主。
					// map 读取到对应的属性为null没有意义（有可能 map 中没有该属性），允许空则会 埋坑，故删除。
					// 只做 instanceof 判断，它会直接忽略掉 null 值。
					e.visitTypeInsn(Opcodes.INSTANCEOF, setterType.toString());
					e.visitJumpInsn(Opcodes.IFEQ, l0);
					e.load_local(targetLocal);
					e.load_local(var);
				}
				e.unbox_or_zero(setterType);
				e.invoke(write);
				// 返回值，判断 链式 bean
				Class<?> returnType = writeMethod.getReturnType();
				if (!returnType.equals(Void.TYPE)) {
					e.pop();
				}
				e.visitLabel(l0);
			}
			e.return_value();
			e.end_method();
			ce.end_class();
		}
	}
}
