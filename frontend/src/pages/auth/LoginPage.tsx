import React, { useState, useEffect } from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { Helmet } from 'react-helmet-async'
import { motion } from 'framer-motion'
import { EyeIcon, EyeOffIcon, LockIcon, UserIcon } from 'lucide-react'
import toast from 'react-hot-toast'

// Components
import { Button, Input, Card } from '@/components/ui'

// Store
import { useAuthActions } from '@/store/auth'

// Types
import type { LoginRequest } from '@/types'

// Validation schema
const loginSchema = z.object({
  username: z
    .string()
    .min(1, 'Username is required')
    .min(3, 'Username must be at least 3 characters')
    .max(50, 'Username must be less than 50 characters')
    .regex(/^[a-zA-Z0-9_]+$/, 'Username can only contain letters, numbers, and underscores'),
  password: z
    .string()
    .min(1, 'Password is required')
    .min(6, 'Password must be at least 6 characters')
    .max(100, 'Password must be less than 100 characters'),
})

type LoginFormData = z.infer<typeof loginSchema>

const LoginPage: React.FC = () => {
  const { login } = useAuthActions()
  const navigate = useNavigate()
  const location = useLocation()
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)

  const {
    register,
    handleSubmit,
    formState: { errors },
    setFocus,
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: '',
      password: '',
    },
  })

  // Focus username field on mount
  useEffect(() => {
    setFocus('username')
  }, [setFocus])

  // Handle form submission
  const onSubmit = async (data: LoginFormData) => {
    setIsLoading(true)

    try {
      await login(data)
      
      // Redirect to intended page or dashboard
      const from = (location.state as any)?.from || '/dashboard'
      navigate(from, { replace: true })
      
    } catch (error: any) {
      console.error('Login error:', error)
      // Error handling is done in the auth store
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <>
      <Helmet>
        <title>Login - SMM Panel</title>
        <meta name="description" content="Login to your SMM Panel account to manage your social media marketing orders" />
      </Helmet>

      <div className="min-h-screen flex flex-col justify-center py-12 sm:px-6 lg:px-8 bg-neutral-50 dark:bg-neutral-900">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="sm:mx-auto sm:w-full sm:max-w-md"
        >
          {/* Logo and Header */}
          <div className="text-center">
            <motion.div
              initial={{ scale: 0.8 }}
              animate={{ scale: 1 }}
              transition={{ delay: 0.1, type: 'spring', stiffness: 200 }}
              className="mx-auto w-16 h-16 bg-primary-600 rounded-xl flex items-center justify-center shadow-lg"
            >
              <span className="text-white font-bold text-2xl">S</span>
            </motion.div>
            
            <motion.h1
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ delay: 0.2 }}
              className="mt-6 text-3xl font-extrabold text-neutral-900 dark:text-neutral-100"
            >
              Welcome back
            </motion.h1>
            
            <motion.p
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              transition={{ delay: 0.3 }}
              className="mt-2 text-sm text-neutral-600 dark:text-neutral-400"
            >
              Sign in to your SMM Panel account
            </motion.p>
          </div>

          {/* Login Form */}
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.4 }}
            className="mt-8"
          >
            <Card>
              <form onSubmit={handleSubmit(onSubmit)} className="space-y-6">
                {/* Username Field */}
                <div>
                  <label htmlFor="username" className="sr-only">
                    Username
                  </label>
                  <div className="relative">
                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                      <UserIcon className="h-5 w-5 text-neutral-400" />
                    </div>
                    <input
                      {...register('username')}
                      id="username"
                      type="text"
                      autoComplete="username"
                      placeholder="Username"
                      className={`appearance-none relative block w-full pl-10 pr-3 py-2 border ${
                        errors.username
                          ? 'border-danger-300 focus:border-danger-500 focus:ring-danger-500'
                          : 'border-neutral-300 dark:border-neutral-600 focus:border-primary-500 focus:ring-primary-500'
                      } placeholder-neutral-500 dark:placeholder-neutral-400 text-neutral-900 dark:text-neutral-100 rounded-md focus:outline-none focus:ring-2 focus:ring-offset-2 sm:text-sm dark:bg-neutral-800`}
                    />
                  </div>
                  {errors.username && (
                    <p className="mt-1 text-sm text-danger-600 dark:text-danger-400">
                      {errors.username.message}
                    </p>
                  )}
                </div>

                {/* Password Field */}
                <div>
                  <label htmlFor="password" className="sr-only">
                    Password
                  </label>
                  <div className="relative">
                    <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                      <LockIcon className="h-5 w-5 text-neutral-400" />
                    </div>
                    <input
                      {...register('password')}
                      id="password"
                      type={showPassword ? 'text' : 'password'}
                      autoComplete="current-password"
                      placeholder="Password"
                      className={`appearance-none relative block w-full pl-10 pr-10 py-2 border ${
                        errors.password
                          ? 'border-danger-300 focus:border-danger-500 focus:ring-danger-500'
                          : 'border-neutral-300 dark:border-neutral-600 focus:border-primary-500 focus:ring-primary-500'
                      } placeholder-neutral-500 dark:placeholder-neutral-400 text-neutral-900 dark:text-neutral-100 rounded-md focus:outline-none focus:ring-2 focus:ring-offset-2 sm:text-sm dark:bg-neutral-800`}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute inset-y-0 right-0 pr-3 flex items-center"
                    >
                      {showPassword ? (
                        <EyeOffIcon className="h-5 w-5 text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300" />
                      ) : (
                        <EyeIcon className="h-5 w-5 text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300" />
                      )}
                    </button>
                  </div>
                  {errors.password && (
                    <p className="mt-1 text-sm text-danger-600 dark:text-danger-400">
                      {errors.password.message}
                    </p>
                  )}
                </div>

                {/* Remember Me & Forgot Password */}
                <div className="flex items-center justify-between">
                  <div className="flex items-center">
                    <input
                      id="remember-me"
                      name="remember-me"
                      type="checkbox"
                      className="h-4 w-4 text-primary-600 focus:ring-primary-500 border-neutral-300 dark:border-neutral-600 rounded dark:bg-neutral-800"
                    />
                    <label htmlFor="remember-me" className="ml-2 block text-sm text-neutral-900 dark:text-neutral-100">
                      Remember me
                    </label>
                  </div>

                  <div className="text-sm">
                    <Link
                      to="/forgot-password"
                      className="font-medium text-primary-600 hover:text-primary-500 dark:text-primary-400 dark:hover:text-primary-300"
                    >
                      Forgot your password?
                    </Link>
                  </div>
                </div>

                {/* Submit Button */}
                <div>
                  <Button
                    type="submit"
                    loading={isLoading}
                    disabled={isLoading}
                    className="group relative w-full flex justify-center py-2 px-4 border border-transparent text-sm font-medium rounded-md text-white bg-primary-600 hover:bg-primary-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-primary-500 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isLoading ? 'Signing in...' : 'Sign in'}
                  </Button>
                </div>

                {/* Registration Link */}
                <div className="text-center">
                  <p className="text-sm text-neutral-600 dark:text-neutral-400">
                    Don't have an account?{' '}
                    <Link
                      to="/register"
                      className="font-medium text-primary-600 hover:text-primary-500 dark:text-primary-400 dark:hover:text-primary-300"
                    >
                      Sign up now
                    </Link>
                  </p>
                </div>
              </form>
            </Card>
          </motion.div>

          {/* Features */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.6 }}
            className="mt-8"
          >
            <div className="text-center">
              <p className="text-xs text-neutral-500 dark:text-neutral-400 mb-4">
                Trusted by thousands of users worldwide
              </p>
              
              <div className="grid grid-cols-3 gap-4 text-center">
                <div className="bg-white dark:bg-neutral-800 rounded-lg p-3 shadow-sm border border-neutral-200 dark:border-neutral-700">
                  <div className="text-lg font-bold text-primary-600 dark:text-primary-400">
                    99.9%
                  </div>
                  <div className="text-xs text-neutral-500 dark:text-neutral-400">
                    Uptime
                  </div>
                </div>
                
                <div className="bg-white dark:bg-neutral-800 rounded-lg p-3 shadow-sm border border-neutral-200 dark:border-neutral-700">
                  <div className="text-lg font-bold text-success-600 dark:text-success-400">
                    24/7
                  </div>
                  <div className="text-xs text-neutral-500 dark:text-neutral-400">
                    Support
                  </div>
                </div>
                
                <div className="bg-white dark:bg-neutral-800 rounded-lg p-3 shadow-sm border border-neutral-200 dark:border-neutral-700">
                  <div className="text-lg font-bold text-warning-600 dark:text-warning-400">
                    Fast
                  </div>
                  <div className="text-xs text-neutral-500 dark:text-neutral-400">
                    Delivery
                  </div>
                </div>
              </div>
            </div>
          </motion.div>

          {/* Footer */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.8 }}
            className="mt-8 text-center"
          >
            <p className="text-xs text-neutral-400 dark:text-neutral-500">
              © 2024 SMM Panel. All rights reserved.
            </p>
            <div className="mt-2 flex justify-center space-x-4 text-xs">
              <a href="/terms" className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300">
                Terms of Service
              </a>
              <a href="/privacy" className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300">
                Privacy Policy
              </a>
              <a href="/support" className="text-neutral-400 hover:text-neutral-600 dark:hover:text-neutral-300">
                Support
              </a>
            </div>
          </motion.div>
        </motion.div>
      </div>
    </>
  )
}

export default LoginPage
